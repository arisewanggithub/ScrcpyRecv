package org.screencopy.receiver.media

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class SrtCloseOwner { USER_STOP, REMOTE_STOP, ERROR, SURFACE_RETIRED, LOADER_CANCEL }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object SrtFailureDetails {
    private val native = Regex("^SRT (send|receive|wait|connect|accept|bind|listen|startup|socket option|create socket|create epoll|register epoll|local port|caller stream id|stream id|connection handshake):[^\\r\\n]* \\(code ([0-9]{1,6})\\)$")
    fun chain(error: Throwable): List<Throwable> {
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        val result = mutableListOf<Throwable>()
        var next: Throwable? = error
        while (next != null && result.size < 8 && seen.put(next, true) == null) {
            result.add(next); next = next.cause
        }
        return result
    }
    private fun type(error: Throwable): String = mapOf(
        "androidx.media3.common.ParserException" to "PARSER",
        "android.media.MediaCodec\$CodecException" to "CODEC",
        "androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException" to "DECODER",
        "androidx.media3.common.PlaybackException" to "PLAYBACK",
        "org.screencopy.receiver.protocol.ProtocolException" to "PROTOCOL"
    )[error.javaClass.name] ?: when (error) {
        is IOException -> "IO"
        is IllegalArgumentException -> "INVALID_INPUT"
        is IllegalStateException -> "INVALID_STATE"
        is InterruptedException -> "INTERRUPTED"
        else -> "OTHER"
    }
    fun snapshot(text: String): JSONObject {
        val numeric = setOf("elapsed_ms", "packets_sent", "packets_received", "send_loss", "receive_loss",
            "acknowledgements_sent", "acknowledgements_received", "negative_acknowledgements_sent", "negative_acknowledgements_received",
            "retransmissions", "bytes_sent", "bytes_received", "send_buffer_packets", "receive_buffer_packets",
            "send_buffer_bytes", "receive_buffer_bytes",
            "send_buffer_available_bytes", "receive_buffer_available_bytes", "rtt_ms", "snapshot_error_code")
        val enums = mapOf("snapshot" to setOf("available", "unavailable"),
            "snapshot_unavailable" to setOf("closed", "busy", "stats_error", "no_socket", "not_recorded", "query_error"),
            "state" to setOf("UNKNOWN", "CLOSED", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
            "last_peer_response_ms" to setOf("unavailable"))
        return JSONObject().apply {
            text.take(2048).split(' ').take(24).forEach { token ->
                val pair = token.split('=', limit = 2)
                if (pair.size == 2 && !has(pair[0]) &&
                    (pair[1] in enums[pair[0]].orEmpty() || pair[0] in numeric &&
                        pair[1].matches(Regex("-?[0-9]{1,20}(\\.[0-9]{1,6})?")))) put(pair[0], pair[1])
            }
        }.let { if (it.has("snapshot")) it else JSONObject() }
    }
    fun describe(error: Throwable): JSONObject {
        val causes = chain(error)
        val root = causes.last()
        var category = type(root)
        var operation = "NONE"
        var code: Int? = null
        for (cause in causes.asReversed()) {
            val message = cause.message.orEmpty().substringBefore(" | snapshot ")
            val match = native.matchEntire(message)
            if (match != null) {
                code = match.groupValues[2].toInt()
                operation = match.groupValues[1].uppercase().replace(' ', '_')
                category = when (code) { 2001 -> "BROKEN"; 2002 -> "NOT_CONNECTED"; else -> "NATIVE_ERROR" }
                break
            }
            val known = when (message) {
                "SRT connection closed", "SRT input closed", "SRT session closed" -> "CLOSED"
                "SRT input not open" -> "NOT_OPEN"
                "SRT input EOF", "Media3 live input ended" -> "EOF"
                "SRT read made no progress" -> "NO_PROGRESS"
                "SRT connection timed out" -> "CONNECT_TIMEOUT"
                "SRT control timeout" -> "CONTROL_TIMEOUT"
                "SRT control write failed" -> "CONTROL_WRITE_FAILED"
                else -> null
            }
            if (known != null) { category = known; break }
        }
        val nativeSnapshot = causes.asReversed().map {
            snapshot(it.message.orEmpty().substringAfter(" | snapshot ", ""))
        }.firstOrNull { it.length() > 0 } ?: JSONObject().put("snapshot", "unavailable").put("snapshot_unavailable", "not_recorded")
        val stack = JSONArray()
        causes.asReversed().forEachIndexed { depth, cause ->
            cause.stackTrace.filter {
                (it.className.startsWith("org.screencopy.receiver.") || it.className.startsWith("androidx.media3.") ||
                    it.className.startsWith("java.io.") || it.className.startsWith("java.nio.")) &&
                    it.className.matches(Regex("[A-Za-z_][A-Za-z0-9_.$]{0,199}")) &&
                    it.methodName.matches(Regex("[A-Za-z_][A-Za-z0-9_.$]{0,199}"))
            }.take(4).forEach {
                if (stack.length() < 12) stack.put("root_depth=$depth at=${it.className}.${it.methodName} line=${it.lineNumber.coerceAtLeast(-1)}")
            }
        }
        return JSONObject().put("category", category).put("operation", operation).put("native_code", code ?: JSONObject.NULL)
            .put("player_error_code", causes.filterIsInstance<androidx.media3.common.PlaybackException>().firstOrNull()?.errorCode ?: JSONObject.NULL)
            .put("root_exception", type(root)).put("chain_truncated", root.cause != null)
            .put("causes", JSONArray(causes.mapIndexed { depth, cause -> "depth=$depth type=${type(cause)}" }))
            .put("stack", stack).put("native_snapshot", nativeSnapshot)
    }
    fun summary(error: Throwable): String = describe(error).let {
        "root=${it.getString("root_exception")} category=${it.getString("category")} operation=${it.getString("operation")} code=${it.get("native_code")}"
    }
}

internal object SrtReceiverFailureStore {
    @Synchronized fun record(prefs: SharedPreferences, json: JSONObject): Boolean {
        try {
            val session = json.getString("session")
            val kind = json.getString("event")
            if (kind == "START") return prefs.edit().putString("currentSession", session)
                .putString("currentSessionStartedAt", json.getString("timestamp_ms")).commit()
            if (prefs.getString("currentSession", null) != session) return true
            val key = when (kind) { "FAILURE" -> "lastFailure"; "CLOSE" -> "lastClose"; else -> return true }
            if (prefs.getString("${key}Session", null) == session) return true
            return prefs.edit().putString("${key}Session", session).putString(key, json.toString()).commit()
        } catch (_: Exception) {
            return false
        }
    }
}

internal class SrtSessionDiagnostics(
    private val session: String, private val videoCodec: String, private val audioCodec: String?,
    private val persist: (JSONObject) -> Unit, private val clock: () -> Long = System::currentTimeMillis
) {
    private val failed = AtomicBoolean()
    private val closeOwner = AtomicReference<SrtCloseOwner?>()
    private val lastRead = AtomicLong()
    private val lastWrite = AtomicLong()
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var state = 0
    @Volatile private var phase = "Initializing"
    private fun base(event: String) = JSONObject().put("event", event).put("session", session)
        .put("timestamp_ms", clock().toString()).put("video_codec", videoCodec.takeIf { it in setOf("h264", "h265", "av1") } ?: "UNKNOWN")
        .put("audio_codec", audioCodec?.takeIf { it in setOf("opus", "aac") } ?: "NONE")
        .put("width", width).put("height", height).put("player_state", state)
        .put("phase", phase)
        .put("last_control_received_ms", lastRead.get().toString()).put("last_control_sent_ms", lastWrite.get().toString())
    fun start() = persist(base("START"))
    fun hasFailure() = failed.get()
    fun control(received: Boolean) { (if (received) lastRead else lastWrite).set(clock()) }
    fun videoSize(width: Int, height: Int) { this.width = width; this.height = height }
    fun state(value: Int) { state = value }
    fun phase(value: String) { phase = value.takeIf { it in setOf("Setup", "Negotiating", "Initializing tracks", "Playing") } ?: "UNKNOWN" }
    @Synchronized fun failure(stage: String, error: Throwable) {
        if (failed.get()) return
        val owner = closeOwner.get()
        if (owner != null && !(owner == SrtCloseOwner.LOADER_CANCEL && stage in setOf("PLAYER_ERROR", "LOAD_ERROR"))) return
        val details = SrtFailureDetails.describe(error)
        if (owner == SrtCloseOwner.LOADER_CANCEL && details.getString("category") in setOf("CLOSED", "NOT_OPEN", "INTERRUPTED")) return
        if (!failed.compareAndSet(false, true)) return
        persist(base("FAILURE").put("stage", stage.takeIf {
            it in setOf("OPEN", "READ", "EOF", "PLAYER_ERROR", "LOAD_ERROR", "STATE_ENDED", "MAIN_ERROR", "CONTROL_ERROR")
        } ?: "UNKNOWN").put("close_owner", owner?.name ?: "NONE")
            .put("details", details))
    }
    @Synchronized fun close(owner: SrtCloseOwner, snapshot: String, reads: Long, completed: Long, bytes: Long) {
        if (!closeOwner.compareAndSet(null, owner)) return
        persist(base("CLOSE").put("owner", owner.name).put("native_snapshot", SrtFailureDetails.snapshot(snapshot))
            .put("read_calls", reads.toString()).put("completed_reads", completed.toString()).put("bytes_received", bytes.toString()))
    }
    companion object {
        fun create(context: Context, streamId: String, video: String, audio: String?): SrtSessionDiagnostics {
            check(streamId.matches(Regex("sc-[0-9a-f]{16}"))) { "Invalid diagnostic session" }
            val session = streamId.removePrefix("sc-").toLongOrNull(16)?.toString()
                ?: throw IOException("Invalid diagnostic session")
            val prefs = context.getSharedPreferences("receiver-diagnostics", Context.MODE_PRIVATE)
            return SrtSessionDiagnostics(session, video, audio, { json ->
                if (!SrtReceiverFailureStore.record(prefs, json))
                    android.util.Log.e("ScreenCopySrt", "diagnostic_store=WRITE_FAILED")
                android.util.Log.i("ScreenCopySrt", "receiver_srt_${json.getString("event").lowercase()} $json")
            })
        }
    }
}
