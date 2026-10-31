package org.screencopy.receiver.media

import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer

internal data class VideoOutput(val index: Int, val ptsUs: Long, val size: Int, val flags: Int)

internal interface VideoDecoder : AutoCloseable {
    val description: String
    fun dequeueInput(): Int
    fun input(index: Int): ByteBuffer?
    fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean)
    fun dequeueOutput(): VideoOutput?
    fun releaseOutput(index: Int, renderAtNs: Long?)
}

internal class VideoCodecFailure(
    val errorCode: Int, val recoverable: Boolean, val transient: Boolean, cause: Exception
) : IllegalStateException("Video codec operation failed", cause)

internal class AndroidVideoDecoder(format: VideoFormat, surface: Surface, onPresented: () -> Unit) : VideoDecoder {
    private val decoder: MediaCodec
    private val info = MediaCodec.BufferInfo()
    override val description: String

    init {
        var stage = VideoStage.SELECT
        var opened: MediaCodec? = null
        try {
            val mediaFormat = VideoPlayer.mediaFormat(format)
            val name = findPreferredDecoder(mediaFormat)
                ?: throw PlaybackException("No device decoder supports the requested video format", "UNSUPPORTED_CONFIG")
            stage = VideoStage.CREATE
            val created = codecCall { MediaCodec.createByCodecName(name) }
            opened = created
            stage = VideoStage.CONFIGURE
            codecCall { created.configure(mediaFormat, surface, null, 0) }
            codecCall { created.setOnFrameRenderedListener({ _, _, _ -> onPresented() }, Handler(Looper.getMainLooper())) }
            stage = VideoStage.START
            codecCall { created.start() }
            val software = codecCall { isSoftwareDecoder(created.codecInfo) }
            description = "Video decoder: $name${if (software) " (software; latency may be higher)" else ""}"
            decoder = created
        } catch (e: Exception) {
            try { opened?.release() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw VideoInitializationFailure(stage, e)
        }
    }

    override fun dequeueInput(): Int = codecCall { decoder.dequeueInputBuffer(0) }
    override fun input(index: Int): ByteBuffer? = codecCall { decoder.getInputBuffer(index) }
    override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) = codecCall {
        decoder.queueInputBuffer(index, 0, size, ptsUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
    }
    override fun dequeueOutput(): VideoOutput? = codecCall {
        val index = decoder.dequeueOutputBuffer(info, 0)
        if (index >= 0) VideoOutput(index, info.presentationTimeUs, info.size, info.flags) else null
    }
    override fun releaseOutput(index: Int, renderAtNs: Long?): Unit = codecCall {
        if (renderAtNs == null) decoder.releaseOutputBuffer(index, false)
        else decoder.releaseOutputBuffer(index, renderAtNs)
    }
    override fun close() {
        try {
            codecCall { decoder.stop() }
        } catch (e: Exception) {
            try { decoder.release() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
        codecCall { decoder.release() }
    }

    private inline fun <T> codecCall(operation: () -> T): T = try {
        operation()
    } catch (e: MediaCodec.CodecException) {
        throw VideoCodecFailure(e.errorCode, e.isRecoverable, e.isTransient, e)
    }
}
