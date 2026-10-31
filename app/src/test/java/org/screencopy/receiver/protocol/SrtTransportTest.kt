package org.screencopy.receiver.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SrtTransportTest {
    private val fixture = checkNotNull(javaClass.getResourceAsStream("/srt-fmp4.json"))
        .use { StrictJson.decode(it.readBytes()) }
    private fun transport() = JSONObject(fixture.obj("requestTransport").toString())
    private fun values(name: String): List<Any> = fixture.array(name).let { array ->
        (0 until array.length()).map { array.get(it) }
    }
    private fun request() = checkNotNull(javaClass.getResourceAsStream("/receiver-orientation.json"))
        .use { StrictJson.decode(it.readBytes()).obj("baseRequest") }

    @Test fun exactTransportRoundTripsAndPinsNumericPeerAndPositiveSession() {
        val parsed = Request.parse(request().put("mediaTransport", transport())).mediaTransport as SrtTransport
        assertEquals(100, parsed.latencyMs)
        assertEquals(fixture.obj("requestTransport").string("url"), parsed.url)
        parsed.validatePeer(fixture.string("controlPeer"))
        parsed.validateSession(fixture.integer("session").toLong())
        for (session in listOf(0L, -1L, 2L)) assertThrows(ProtocolException::class.java) { parsed.validateSession(session) }
        assertThrows(ProtocolException::class.java) { parsed.validatePeer("127.0.0.2") }
        assertNull(Request.parse(request()).mediaTransport)
    }

    @Test fun rejectsMalformedUrlsFieldsTypesAndNoncanonicalIdsWithoutFallback() {
        val invalid = listOf("url" to "invalidUrls", "latencyMs" to "invalidLatencyMs",
            "container" to "invalidContainers", "streamId" to "invalidStreamIds").flatMap { (field, name) ->
            values(name).map { transport().put(field, it) }
        } + listOf("srt://user@192.168.1.25:43517", "srt://999.0.0.1:43517",
            "srt://127.00.0.1:43517").map { transport().put("url", it) } +
            transport().put("extra", true)
        invalid.forEach { value ->
            assertEquals(value.toString(), "UNSUPPORTED_CONFIG", assertThrows(ProtocolException::class.java) {
                val parsed = Request.parse(request().put("mediaTransport", value)).mediaTransport as SrtTransport
                parsed.validatePeer(fixture.string("controlPeer"))
                parsed.validateSession(fixture.integer("session").toLong())
            }.code)
        }
        for (field in listOf("kind", "container", "url", "latencyMs", "streamId")) {
            val value = transport().also { it.remove(field) }
            assertThrows(ProtocolException::class.java) { Request.parse(request().put("mediaTransport", value)) }
        }
        for (kind in listOf("rtsp", "SRT", "custom", "matroska", "")) {
            assertEquals("UNSUPPORTED_CONFIG", assertThrows(ProtocolException::class.java) {
                Request.parse(request().put("mediaTransport", transport().put("kind", kind)))
            }.code)
        }
    }

    @Test fun srtNeverSubstitutesCodecsIncludingDisabledUnsupportedAudioSelection() {
        for (video in listOf("h264", "h265", "av1")) for (audio in listOf("opus", "aac", "raw")) {
            for (enabled in listOf(true, false)) {
                val value = request().put("mediaTransport", transport())
                value.obj("video").put("codec", video)
                value.obj("audio").put("codec", audio).put("enabled", enabled)
                if (!enabled || audio in listOf("opus", "aac")) {
                    val parsed = Request.parse(value)
                    assertNotNull(parsed.mediaTransport)
                    assertEquals(video, parsed.videoCodec)
                    assertEquals(if (enabled) audio else null, parsed.audioCodec)
                }
                else assertEquals("UNSUPPORTED_CONFIG", assertThrows(ProtocolException::class.java) { Request.parse(value) }.code)
            }
        }
        for (latency in values("latencyBoundsMs")) assertEquals(latency,
            SrtTransport.parse(transport().put("latencyMs", latency)).latencyMs)
    }
}
