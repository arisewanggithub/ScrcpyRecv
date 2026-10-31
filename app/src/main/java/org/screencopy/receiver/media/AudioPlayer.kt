package org.screencopy.receiver.media

import android.media.AudioFormat as PlatformAudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioPlayer(
    private val format: AudioFormat,
    private val clock: PlaybackClock,
    failure: (String, String) -> Unit,
    outputFactory: () -> AudioOutput = { PlatformAudioOutput(format, failure) },
    private val onClockCorrection: (AudioClockCorrection) -> Unit = {},
    private val onOutputTiming: (AudioOutputTiming) -> Unit = {},
    private val onUnderrunRecovery: (AudioUnderrunDiagnostic) -> Unit = {},
    opusFactory: (AudioFormat) -> OpusPacketDecoder = { OpusDecoder(it.channels, it.outputGainQ8) }
) : TrackWorker("ScreenCopy-audio", 512 * 1024, { code, detail -> failure(if (code == "DECODER_FAILED") "AUDIO_FAILED" else code, detail) }, track = 2) {
    private val output: AudioOutput
    private val opus: OpusPacketDecoder?
    private val aac: MediaCodec?
    private val info = if (format.codec == "aac") MediaCodec.BufferInfo() else null
    private var waiting: AccessUnit? = null
    private var pending: DecodedAudio? = null
    private val startup = StartupTrim(format.preSkip, format.sampleRate)
    private var totalFrames = 0L
    @Volatile private var firstPtsUs: Long? = null
    private val underruns = AudioUnderrunMonitor()
    private var lastUnderrunObservationNs: Long? = null
    private var lastCorrectionNs = 0L
    private var stage = "STARTUP_CHECK"
    private var lastClockEventNs: Long? = null
    private var lastLoggedOffsetUs = 0L
    private var pendingClockCorrection: AudioClockCorrection? = null
    private var lastDecodedPtsUs: Long? = null

    val decoderDescription: String
        get() = when (format.codec) {
            "opus" -> "bundled libopus 1.5.2"
            "aac" -> checkNotNull(aac).name
            else -> "PCM s16le direct"
        }

    init {
        require(format.channels in 1..2) { "Only mono/stereo audio is supported" }
        require(format.sampleRate in listOf(8000, 12000, 16000, 24000, 32000, 44100, 48000, 96000)) { "Unsupported audio sample rate" }
        require(format.preSkip in 0..65535) { "Invalid audio pre-skip" }
        require(format.codec in listOf("opus", "aac", "pcm")) { "Unsupported audio codec ${format.codec}" }
        require(format.codec != "opus" || format.sampleRate == 48000) { "Opus output clock must be 48000 Hz" }
        output = outputFactory()
        try {
            opus = if (format.codec == "opus") opusFactory(format) else null
            aac = if (format.codec == "aac") createAac(format) else null
        } catch (e: Exception) {
            output.close()
            throw e
        }
    }

    override fun step() {
        stage = "STARTUP_CHECK"
        clock.checkStartup()
        if (pending != null && clock.canPrepareAudio()) {
            flushPending()
            return
        }
        if (format.codec == "aac") stepAac()
        else next()?.let { packet ->
            stage = if (opus != null) "OPUS_DECODE" else "PCM_DECODE"
            val samples = opus?.decode(packet.bytes) ?: pcm16(packet.bytes)
            write(samples, packet.ptsUs)
        }
        observeUnderruns()
    }

    private fun stepAac() {
        stage = "AAC_INPUT"
        val decoder = checkNotNull(aac)
        val info = checkNotNull(info)
        if (waiting == null) waiting = next()
        waiting?.let { packet ->
            val index = decoder.dequeueInputBuffer(0)
            if (index >= 0) {
                val input = checkNotNull(decoder.getInputBuffer(index))
                require(input.capacity() >= packet.bytes.size) { "AAC packet exceeds decoder capacity" }
                input.clear()
                input.put(packet.bytes)
                decoder.queueInputBuffer(index, 0, packet.bytes.size, packet.ptsUs, 0)
                waiting = null
            }
        }
        val index = decoder.dequeueOutputBuffer(info, 0)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            stage = "AAC_FORMAT"
            val actual = decoder.outputFormat
            require(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == format.sampleRate &&
                actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == format.channels &&
                (!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                    actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == PlatformAudioFormat.ENCODING_PCM_16BIT)
            ) { "AAC decoder changed requested PCM output format" }
        } else if (index >= 0) {
            stage = "AAC_OUTPUT"
            try {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    validatePcmLength(info.size)
                    val buffer = checkNotNull(decoder.getOutputBuffer(index))
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    write(pcm16(bytes), info.presentationTimeUs)
                }
            } finally { decoder.releaseOutputBuffer(index, false) }
        }
    }

    private fun write(samples: ShortArray, ptsUs: Long) {
        stage = "STARTUP_TRIM"
        if (ptsUs < 0) throw PlaybackException("Decoded audio PTS is negative within a generation")
        if (format.codec != "opus" && lastDecodedPtsUs?.let { ptsUs < it } == true)
            throw PlaybackException("Decoded audio PTS decreased within a generation")
        lastDecodedPtsUs = ptsUs
        clock.noteAudioPacket()
        require(samples.size % format.channels == 0) { "PCM packet is not frame aligned" }
        val slice = startup.apply(samples.size / format.channels, ptsUs)
        if (slice.frames == 0) return
        if (firstPtsUs == null) {
            // Until the first video packet, retain only the latest decoded preroll packet.
            pending = DecodedAudio(samples, slice)
            flushPending()
        } else writePcm(samples, slice)
    }

    private fun flushPending() {
        stage = "STARTUP_SYNC"
        val decoded = pending ?: return
        while (running.get()) {
            val slice = clock.prepareAudio(decoded.slice, format.sampleRate) ?: return
            if (slice.frames == 0) { pending = null; return }
            if (!clock.awaitAudioStart(slice.audiblePtsUs) { running.get() }) continue
            pending = null
            writePcm(decoded.samples, slice)
            return
        }
    }

    private fun writePcm(samples: ShortArray, slice: TrimmedAudio) {
        val trim = slice.skipFrames
        val frames = slice.frames
        if (frames == 0) return
        val audiblePtsUs = slice.audiblePtsUs
        val starting = firstPtsUs == null
        if (starting) {
            stage = "OUTPUT_START"
            firstPtsUs = audiblePtsUs
            clock.anchorAudio(audiblePtsUs, format.sampleRate, output::positionUs)
        }
        stage = "SOURCE_MAP"
        val correction = clock.audioPacket(totalFrames * 1_000_000L / format.sampleRate, audiblePtsUs)
        if (correction != null) pendingClockCorrection = correction
        pendingClockCorrection?.let {
            val now = clock.monotonicNs()
            if (abs(Math.subtractExact(it.afterOffsetUs, lastLoggedOffsetUs)) >= 1000 &&
                lastClockEventNs?.let { previous -> now - previous >= 1_000_000_000L } != false) {
                onClockCorrection(it)
                lastClockEventNs = now
                lastLoggedOffsetUs = it.afterOffsetUs
                pendingClockCorrection = null
            }
        }
        if (starting) {
            stage = "OUTPUT_START"
            output.play()
            underruns.start(output.underrunCount)
        }
        var offset = trim * format.channels
        val initialOffset = offset
        val end = offset + frames * format.channels
        val started = clock.monotonicNs()
        var lastProgress = started
        var zeroWrites = 0
        while (running.get() && offset < end) {
            stage = "OUTPUT_WRITE"
            val writeStarted = clock.monotonicNs()
            val written = output.write(samples, offset, end - offset)
            val now = clock.monotonicNs()
            check(written >= 0) { "AudioTrack write failed ($written)" }
            if (written == 0) {
                zeroWrites++
                if (now - lastProgress > 250_000_000L) {
                    throw PlaybackException("Audio output stalled for 250 ms without observed write progress;" +
                        " event=WRITE_STALL recent_progress_ms=${(now - lastProgress) / 1_000_000}" +
                        " packet_elapsed_ms=${(now - started) / 1_000_000}" +
                        " remaining_samples=${end - offset} written_samples=${offset - initialOffset}" +
                        " write_call_ms=${(now - writeStarted) / 1_000_000} zero_writes=$zeroWrites" +
                        " playing_state=${output.playbackActive} ${output.timing().logMessage()}")
                }
                Thread.sleep(2)
            } else {
                check(written <= end - offset && written % format.channels == 0) { "AudioTrack returned an invalid sample count" }
                // Bound continuous lack of progress, not the age of a partially accepted packet.
                lastProgress = now
                zeroWrites = 0
                offset += written
                totalFrames += written / format.channels
                clock.audioWritten(totalFrames * 1_000_000L / format.sampleRate)
            }
        }
        observeUnderruns()

        // Regulate actual buffered PCM, not capture-clock phase corrections.
        val now = clock.monotonicNs()
        if (now - lastCorrectionNs > 1_000_000_000L && totalFrames > format.sampleRate) {
            stage = "RATE_CORRECTION"
            val timing = output.timing()
            if (timing.submittedFrames != totalFrames)
                throw AudioClockException(AudioClockDiagnostic(AudioClockFault.SUBMISSION, timing))
            if (timing.submittedFrames - timing.consumedFrames !in 0..timing.bufferFrames.toLong())
                throw AudioClockException(AudioClockDiagnostic(AudioClockFault.CLIENT_BUFFER, timing))
            onOutputTiming(timing)
            val leadUs = timing.bufferedUs
            val speed = when {
                leadUs > 100_000 -> AudioOutputPreparation.MAX_SPEED
                leadUs < 40_000 -> AudioOutputPreparation.MIN_SPEED
                else -> AudioOutputPreparation.NOMINAL_SPEED
            }
            output.setSpeed(speed)
            lastCorrectionNs = now
        }
    }

    private fun observeUnderruns() {
        if (firstPtsUs == null || !running.get()) return
        val now = clock.monotonicNs()
        if (lastUnderrunObservationNs?.let { now - it < 20_000_000L } == true) return
        stage = "UNDERRUN_CHECK"
        lastUnderrunObservationNs = now
        underruns.observe(now, output.underrunCount, output.timing(), queuedPackets(),
            pending != null || waiting != null, output.playbackActive)?.let(onUnderrunRecovery)
    }

    override fun unblock() { output.pause() }

    override fun describeFailure(error: Exception): String =
        "Audio stage=$stage exception=${error.javaClass.simpleName}: ${error.message ?: "operation failed"}"

    override fun release() {
        try {
            clock.detachAudio()
        } finally {
            try {
                aac?.let { try { it.stop() } finally { it.release() } }
            } finally {
                try { opus?.close() } finally { pending = null; output.close() }
            }
        }
    }

    private fun pcm16(bytes: ByteArray): ShortArray {
        validatePcmLength(bytes.size)
        require(bytes.size % (format.channels * 2) == 0) { "PCM s16le data is not frame aligned" }
        return ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }

    private fun validatePcmLength(bytes: Int) {
        if (bytes > format.sampleRate * format.channels * 2 * 120 / 1000)
            throw PlaybackException("Decoded audio packet exceeds 120 ms budget; sender must use smaller packets", "CONGESTION")
    }

    private data class DecodedAudio(val samples: ShortArray, val slice: TrimmedAudio)

    companion object {
        private fun createAac(format: AudioFormat): MediaCodec {
            require(format.configuration.isNotEmpty()) { "AAC AudioSpecificConfig is required" }
            val mediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", format.sampleRate, format.channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(format.configuration))
                setInteger(MediaFormat.KEY_PCM_ENCODING, PlatformAudioFormat.ENCODING_PCM_16BIT)
            }
            val name = findPreferredDecoder(mediaFormat)
                ?: throw PlaybackException("No compatible AAC decoder")
            return MediaCodec.createByCodecName(name).also {
                try {
                    it.configure(mediaFormat, null, null, 0)
                    it.start()
                } catch (e: Exception) {
                    it.release()
                    throw e
                }
            }
        }
    }
}
