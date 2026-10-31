package org.screencopy.receiver.media

import kotlin.math.abs
import kotlin.math.floor

internal interface RateAudioTrack : AudioOutput {
    val initialized: Boolean
    val sampleRate: Int
    val channels: Int
    val pcm16: Boolean
    val bufferFrames: Int
    val capacityFrames: Int
    val isPlaying: Boolean
    val speed: Float
    val pitch: Float
    val failOnUnsupported: Boolean
}

internal interface AudioOutputPlatform {
    fun minimumBytes(format: AudioFormat): Int
    fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack
}

enum class OutputPreparationReason { RATE_UNSUPPORTED, BUFFER_LIMIT, TRACK_INITIALIZATION, FORMAT_MISMATCH, PLAY_STATE, PARAMS_MISMATCH }

data class OutputPreparationDiagnostic(
    val reason: OutputPreparationReason,
    val sampleRate: Int,
    val channels: Int,
    val minimumFrames: Int,
    val requestedFrames: Int,
    val bufferFrames: Int,
    val capacityFrames: Int,
    val maxFrames: Int,
    val speed: Float,
    val actualSampleRate: Int = 0,
    val actualChannels: Int = 0
) {
    fun logMessage() = "output_reason=$reason sample_rate=$sampleRate channels=$channels minimum_frames=$minimumFrames" +
        " requested_frames=$requestedFrames buffer_frames=$bufferFrames capacity_frames=$capacityFrames" +
        " max_frames=$maxFrames intended_speed=$speed actual_sample_rate=$actualSampleRate actual_channels=$actualChannels"

    companion object {
        internal fun fromLocalDetail(detail: String): OutputPreparationDiagnostic? {
            val fields = detail.substringAfter("Audio output rate preparation unsupported: ", "")
                .split(' ').mapNotNull { word ->
                    val pair = word.split('=', limit = 2)
                    if (pair.size == 2) pair[0] to pair[1] else null
                }.toMap()
            val reason = OutputPreparationReason.entries.firstOrNull { it.name == fields["output_reason"] } ?: return null
            fun integer(key: String) = fields[key]?.toIntOrNull()
            val speed = fields["intended_speed"]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            return OutputPreparationDiagnostic(reason, integer("sample_rate") ?: return null,
                integer("channels") ?: return null, integer("minimum_frames") ?: return null,
                integer("requested_frames") ?: return null, integer("buffer_frames") ?: return null,
                integer("capacity_frames") ?: return null, integer("max_frames") ?: return null, speed,
                integer("actual_sample_rate") ?: return null, integer("actual_channels") ?: return null)
        }
    }
}

class AudioOutputPreparationException(val diagnostic: OutputPreparationDiagnostic, cause: Exception? = null) :
    PlaybackException("Audio output rate preparation unsupported: ${diagnostic.logMessage()}", "UNSUPPORTED_CONFIG") {
    init { if (cause != null) initCause(cause) }
}

internal data class AudioBufferPlan(
    val sampleRate: Int, val channels: Int, val minimumFrames: Int, val frames: Int, val bytes: Int
)

internal object AudioOutputPreparation {
    const val MIN_SPEED = 0.995f
    const val MAX_SPEED = 1.005f
    const val NOMINAL_SPEED = 1f
    const val MAX_BUFFER_MS = 130

    fun plan(format: AudioFormat, minimumBytes: Int): AudioBufferPlan {
        require(format.sampleRate in 1..96000 && format.channels in 1..2) { "Invalid PCM output format" }
        val frameBytes = format.channels * 2
        val maxFrames = (format.sampleRate * MAX_BUFFER_MS.toLong() / 1000).toInt()
        val minimumFrames = if (minimumBytes > 0) ((minimumBytes.toLong() + frameBytes - 1) / frameBytes).toInt() else 0
        // Android documents a size greater than speed * minimum; the native route may require more.
        val rateMinimum = floor(minimumFrames * MAX_SPEED.toDouble()).toLong() + 1
        if (minimumBytes <= 0 || maxFrames <= 0 || rateMinimum > maxFrames) {
            throw AudioOutputPreparationException(OutputPreparationDiagnostic(OutputPreparationReason.BUFFER_LIMIT,
                format.sampleRate, format.channels, minimumFrames, maxFrames, 0, 0, maxFrames, MAX_SPEED))
        }
        // Reserve the existing bounded headroom; do not assume the nominal getMinBufferSize covers speed.
        return AudioBufferPlan(format.sampleRate, format.channels, minimumFrames, maxFrames, maxFrames * frameBytes)
    }

    fun open(format: AudioFormat, platform: AudioOutputPlatform, failure: (String, String) -> Unit): Pair<RateAudioTrack, AudioBufferPlan> {
        val plan = plan(format, platform.minimumBytes(format))
        val device = try { platform.create(format, plan.bytes, failure) } catch (e: Exception) {
            throw AudioOutputPreparationException(OutputPreparationDiagnostic(OutputPreparationReason.TRACK_INITIALIZATION,
                plan.sampleRate, plan.channels, plan.minimumFrames, plan.frames, 0, 0, plan.frames, MAX_SPEED), e)
        }
        var checkingSpeed = NOMINAL_SPEED
        fun diagnostic(reason: OutputPreparationReason): OutputPreparationDiagnostic {
            val initialized = device.initialized
            return OutputPreparationDiagnostic(reason, plan.sampleRate, plan.channels, plan.minimumFrames, plan.frames,
                if (initialized) device.bufferFrames else 0, if (initialized) device.capacityFrames else 0,
                plan.frames, checkingSpeed, if (initialized) device.sampleRate else 0, if (initialized) device.channels else 0)
        }
        fun verify() {
            if (!device.initialized) throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.TRACK_INITIALIZATION))
            if (device.sampleRate != plan.sampleRate || device.channels != plan.channels || !device.pcm16)
                throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.FORMAT_MISMATCH))
            if (device.bufferFrames !in plan.minimumFrames..plan.frames || device.capacityFrames !in device.bufferFrames..plan.frames)
                throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.BUFFER_LIMIT))
            if (device.isPlaying) throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.PLAY_STATE))
        }
        try {
            verify()
            for (speed in floatArrayOf(MAX_SPEED, MIN_SPEED, NOMINAL_SPEED)) {
                checkingSpeed = speed
                device.setSpeed(speed)
                verify()
                if (!device.speed.isFinite() || !device.pitch.isFinite() || !device.failOnUnsupported ||
                    abs(device.speed - speed) > 0.00001f || abs(device.pitch - 1f) > 0.00001f)
                    throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.PARAMS_MISMATCH))
            }
            return device to plan
        } catch (e: Exception) {
            val error = if (e is AudioOutputPreparationException) e
                else AudioOutputPreparationException(diagnostic(OutputPreparationReason.RATE_UNSUPPORTED), e)
            try { device.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }
}
