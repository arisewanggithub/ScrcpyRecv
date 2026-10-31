package org.screencopy.receiver.protocol

import org.json.JSONObject
import java.net.URI

sealed interface MediaTransport {
    val kind: String
    val url: String
    fun validatePeer(peer: String)

    companion object {
        fun parse(json: JSONObject): MediaTransport? {
            if (!json.has("mediaTransport")) return null
            return try { when (json.obj("mediaTransport").string("kind")) {
                "srt" -> SrtTransport.parse(json.obj("mediaTransport"))
                else -> throw ProtocolException("UNSUPPORTED_CONFIG", "Unsupported media transport")
            } } catch (e: ProtocolException) {
                throw ProtocolException("UNSUPPORTED_CONFIG", e.message ?: "Invalid media transport")
            }
        }
    }
}

data class SrtTransport(override val url: String, val latencyMs: Int, val streamId: String) : MediaTransport {
    override val kind = "srt"
    override fun validatePeer(peer: String) {
        protocolRequire(URI(url).host.removeSurrounding("[", "]") == peer,
            "SRT host must equal the numeric control sender address", "UNSUPPORTED_CONFIG")
    }

    fun validateSession(session: Long) {
        protocolRequire(session > 0 && streamId == "sc-" + session.toString(16).padStart(16, '0'),
            "SRT stream ID does not match control session", "UNSUPPORTED_CONFIG")
    }

    companion object {
        fun parse(value: JSONObject): SrtTransport = try {
            protocolRequire(value.keys().asSequence().toSet() == setOf("kind", "container", "url", "latencyMs", "streamId"),
                "Invalid SRT transport fields", "UNSUPPORTED_CONFIG")
            protocolRequire(value.string("kind") == "srt" && value.string("container") == "fmp4",
                "SRT requires fragmented MP4", "UNSUPPORTED_CONFIG")
            val url = value.string("url", 512)
            val uri = try { URI(url) } catch (_: Exception) {
                throw ProtocolException("UNSUPPORTED_CONFIG", "Invalid SRT URL")
            }
            val host = uri.host?.removeSurrounding("[", "]").orEmpty()
            val numeric = if (host.contains(':')) host.matches(Regex("[0-9a-fA-F:]+"))
                else host.split('.').let { parts -> parts.size == 4 && parts.all {
                    it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255
                } }
            protocolRequire(uri.scheme == "srt" && numeric && uri.port in 1..65535 &&
                uri.rawPath.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null,
                "SRT requires a numeric peer and explicit port without path/credentials/query/fragment", "UNSUPPORTED_CONFIG")
            val latency = value.integer("latencyMs")
            val streamId = value.string("streamId", 19)
            protocolRequire(latency in 20..2000 && streamId.matches(Regex("sc-[0-9a-f]{16}")),
                "Invalid SRT latency or stream ID", "UNSUPPORTED_CONFIG")
            SrtTransport(url, latency, streamId)
        } catch (e: ProtocolException) {
            throw ProtocolException("UNSUPPORTED_CONFIG", e.message ?: "Invalid SRT transport")
        }
    }
}
