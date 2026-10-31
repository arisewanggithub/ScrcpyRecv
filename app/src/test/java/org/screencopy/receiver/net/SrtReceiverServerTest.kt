package org.screencopy.receiver.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.lifecycle.ResourceSlot
import org.screencopy.receiver.media.SrtPlayback
import org.screencopy.receiver.protocol.*
import org.screencopy.receiver.ui.ReceiverScreenEvent
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SrtReceiverServerTest {
    private val fixture = checkNotNull(javaClass.getResourceAsStream("/srt-fmp4.json"))
        .use { StrictJson.decode(it.readBytes()) }
    private class Player : SrtPlayback {
        @Volatile var result = ""
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        val owners = ConcurrentLinkedQueue<org.screencopy.receiver.media.SrtCloseOwner>()
        override fun start() { starts.incrementAndGet() }
        override fun poll() = result
        override fun close() { closes.incrementAndGet() }
        override fun closeInitiated(owner: org.screencopy.receiver.media.SrtCloseOwner) { owners.add(owner) }
    }

    @Test fun selectsOnlyLibraryPlaybackEchoesBackendAndWaitsForRealReadinessBeforeUiLease() {
        val player = Player()
        val events = ConcurrentLinkedQueue<ReceiverScreenEvent>()
        val leases = AtomicInteger()
        val restored = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", validateRequest = { error("custom validation") },
            clockFactory = { error("custom clock") }, videoFactory = { _, _, _, _ -> error("custom video") },
            audioFactory = { _, _, _ -> error("custom audio") }, srtFactory = { player },
            onScreenEvent = events::add, onPresentationStart = {
                leases.incrementAndGet(); AutoCloseable { restored.incrementAndGet() }
            }).use { server ->
            server.start()
            peer(server).use { socket ->
                hello(socket)
                send(socket, Type.SESSION_REQUEST, settings())
                val accepted = Wire.read(socket.inputStream)
                assertEquals(Type.SESSION_ACCEPT, accepted.type)
                assertEquals("srt", accepted.json().obj("mediaTransport").string("kind"))
                assertFixtureObject(fixture.obj("accept"), accepted.json())
                await { player.starts.get() == 1 }
                assertEquals(0, leases.get())
                assertFalse(events.contains(ReceiverScreenEvent.FramePresented))
                send(socket, Type.PING, JSONObject().put("nonce", 42))
                assertEquals(42, readUntil(socket, Type.PONG).json().integer("nonce"))
                peer(server).use { contender ->
                    hello(contender); send(contender, Type.SESSION_REQUEST, settings())
                    assertEquals("BUSY", Wire.read(contender.inputStream).json().string("code"))
                }
                // Mock boundary only. Not a claim of full application/media acceptance.
                player.result = """{"width":320,"height":240,"decoder":"c2.fixture.avc","firstFrame":true,"audioProof":true}"""
                assertFixtureObject(fixture.obj("ready"), readUntil(socket, Type.STATUS).json())
                await { events.contains(ReceiverScreenEvent.FramePresented) }
                assertEquals(1, leases.get())
                send(socket, Type.STOP, JSONObject().put("reason", "test"))
                readUntil(socket, Type.SESSION_END)
                await { player.closes.get() == 1 && restored.get() == 1 }
                assertEquals(listOf(org.screencopy.receiver.media.SrtCloseOwner.REMOTE_STOP), player.owners.toList())
            }
        }
    }

    @Test fun rejectsCrossEndpointAndMismatchedStreamBeforeAcceptance() {
        for (field in listOf("url", "streamId")) {
            ReceiverServer.forProtocolTests("Receiver", srtFactory = { error("must not create player") }).use { server ->
                server.start()
                peer(server).use { socket ->
                    hello(socket)
                    val settings = settings()
                    settings.obj("mediaTransport").put(field,
                        if (field == "url") "srt://127.0.0.2:43517" else "sc-0000000000000002")
                    send(socket, Type.SESSION_REQUEST, settings)
                    assertEquals("UNSUPPORTED_CONFIG", Wire.read(socket.inputStream).json().string("code"))
                }
            }
        }
    }

    @Test fun removedRtspDescriptorIsRejectedBeforeAnyPlaybackFactory() {
        ReceiverServer.forProtocolTests("Receiver",
            validateRequest = { error("must not fall back to custom") },
            srtFactory = { error("must not create SRT player") }).use { server ->
            server.start()
            peer(server).use { socket ->
                hello(socket)
                val settings = settings().put("mediaTransport", JSONObject()
                    .put("kind", "rtsp").put("url", "rtsp://127.0.0.1:43517/session")
                    .put("preferredTransport", "udp"))
                send(socket, Type.SESSION_REQUEST, settings)
                assertEquals("UNSUPPORTED_CONFIG", Wire.read(socket.inputStream).json().string("code"))
            }

        }
    }

    @Test fun unavailableAcceptedCodecRejectsBeforeSessionAcceptOrPlayerWithoutFallback() {
        for (video in listOf("h264", "h265", "av1")) {
            ReceiverServer.forProtocolTests("Receiver",
                validateRequest = { error("must not fall back to custom") },
                validateSrtCodecs = { request ->
                    assertEquals(video, request.videoCodec)
                    throw ProtocolException("UNSUPPORTED_CONFIG", "Selected platform decoder unavailable")
                }, srtFactory = { error("must not create player for unavailable codec") }).use { server ->
                server.start()
                peer(server).use { socket ->
                    hello(socket)
                    val value = settings()
                    value.obj("video").put("codec", video)
                    value.obj("audio").put("codec", "aac")
                    send(socket, Type.SESSION_REQUEST, value)
                    val rejected = Wire.read(socket.inputStream)
                    assertEquals(Type.REJECT, rejected.type)
                    assertEquals("UNSUPPORTED_CONFIG", rejected.json().string("code"))
                }

            }
        }
    }

    @Test fun hevcChecksCorrectDecoderMimeBeforeAcceptanceAndCreatesOnlySrtPlayer() {
        val order = ConcurrentLinkedQueue<String>()
        val player = Player()
        ReceiverServer.forProtocolTests("Receiver",
            validateRequest = { error("must not select custom TCP") },
            validateSrtCodecs = { request ->
                assertEquals("h265", request.videoCodec)
                assertEquals("video/hevc", Configuration.mime(request.videoCodec))
                order.add("decoder-validated")
            }, srtFactory = { request ->
                assertEquals("h265", request.videoCodec)
                assertEquals(listOf("decoder-validated"), order.toList())
                order.add("player-created")
                player
            }).use { server ->
            server.start()
            peer(server).use { socket ->
                hello(socket)
                val selected = settings()
                selected.obj("video").put("codec", "h265")
                send(socket, Type.SESSION_REQUEST, selected)
                assertEquals(Type.SESSION_ACCEPT, Wire.read(socket.inputStream).type)
                await { player.starts.get() == 1 }
                assertEquals(listOf("decoder-validated", "player-created"), order.toList())
            }
        }
    }

    @Test fun srtRotationUpdatesSurfaceAspectWithoutNewLeasePlayerOrControlSession() {
        val player = Player()
        val sizes = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val events = ConcurrentLinkedQueue<ReceiverScreenEvent>()
        val leases = AtomicInteger()
        val restored = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", srtFactory = { player },
            onVideoSize = { width, height -> sizes.add(width to height) },
            onScreenEvent = events::add,
            onPresentationStart = { leases.incrementAndGet(); AutoCloseable { restored.incrementAndGet() } }
        ).use { server ->
            server.start()
            peer(server).use { socket ->
                hello(socket); send(socket, Type.SESSION_REQUEST, settings())
                assertEquals(Type.SESSION_ACCEPT, Wire.read(socket.inputStream).type)
                await { player.starts.get() == 1 }
                val dimensions = listOf(1080 to 1920, 1920 to 1080, 1080 to 1920)
                for ((index, size) in dimensions.withIndex()) {
                    player.result = JSONObject().put("width", size.first).put("height", size.second)
                        .put("decoder", "c2.fixture.avc").put("firstFrame", true).put("audioProof", true).toString()
                    if (index == 0) assertFixtureObject(fixture.obj("ready"), readUntil(socket, Type.STATUS).json())
                    await { sizes.size == index + 1 }
                    assertEquals(dimensions.take(index + 1), sizes.toList())
                    send(socket, Type.PING, JSONObject().put("nonce", index))
                    assertEquals(index, readUntil(socket, Type.PONG).json().integer("nonce"))
                    assertEquals(1, leases.get())
                    assertEquals(0, restored.get())
                    assertEquals(1, player.starts.get())
                    assertEquals(0, player.closes.get())
                }
                assertEquals(1, events.count { it == ReceiverScreenEvent.FramePresented })
                send(socket, Type.STOP, JSONObject().put("reason", "synthetic rotation boundary complete"))
                readUntil(socket, Type.SESSION_END)
                await { player.closes.get() == 1 && restored.get() == 1 }
            }
        }
    }

    @Test fun firstFrameAndEnabledAudioProofAreBothRequiredButAudioOffDoesNotWaitForAudio() {
        for (audioEnabled in listOf(true, false)) {
            val player = Player()
            val events = ConcurrentLinkedQueue<ReceiverScreenEvent>()
            val leases = AtomicInteger()
            ReceiverServer.forProtocolTests("Receiver", srtFactory = { player },
                onScreenEvent = events::add, onPresentationStart = {
                    leases.incrementAndGet(); AutoCloseable {}
                }).use { server ->
                server.start()
                peer(server).use { socket ->
                    hello(socket)
                    val config = settings()
                    config.obj("audio").put("enabled", audioEnabled)
                    send(socket, Type.SESSION_REQUEST, config)
                    val accepted = Wire.read(socket.inputStream)
                    assertEquals(Type.SESSION_ACCEPT, accepted.type)
                    assertFixtureObject(fixture.obj(if (audioEnabled) "accept" else "acceptAudioOff"), accepted.json())
                    await { player.starts.get() == 1 }
                    player.result = """{"width":320,"height":240,"decoder":"fixture","firstFrame":false,"audioProof":true}"""
                    Thread.sleep(1100)
                    assertEquals(0, leases.get())
                    assertFalse(events.contains(ReceiverScreenEvent.FramePresented))
                    player.result = """{"width":320,"height":240,"decoder":"fixture","firstFrame":true,"audioProof":false}"""
                    if (audioEnabled) {
                        Thread.sleep(1100)
                        assertEquals(0, leases.get())
                        send(socket, Type.PING, JSONObject().put("nonce", 13))
                        assertEquals(13, readUntil(socket, Type.PONG).json().integer("nonce"))
                        player.result = """{"width":320,"height":240,"decoder":"fixture","firstFrame":true,"audioProof":true}"""
                    }
                    assertFixtureObject(fixture.obj("ready"), readUntil(socket, Type.STATUS).json())
                    await { leases.get() == 1 }
                    send(socket, Type.STOP, JSONObject().put("reason", "test"))
                    readUntil(socket, Type.SESSION_END)
                }
            }
        }
    }

    @Test fun customPacketsErrorAndStopDisconnectAndLateCleanupCloseExactlyOnce() {
        for (mode in listOf("config", "media", "audio", "stop", "error", "disconnect", "late")) {
            val player = Player()
            val slot = ResourceSlot<SrtPlayback>()
            val events = ConcurrentLinkedQueue<ReceiverScreenEvent>()
            ReceiverServer.forProtocolTests("Receiver", srtFactory = { player }, srtOutput = slot,
                onScreenEvent = events::add).use { server ->
                server.start()
                peer(server).use { socket ->
                    hello(socket); send(socket, Type.SESSION_REQUEST, settings())
                    assertEquals(Type.SESSION_ACCEPT, Wire.read(socket.inputStream).type)
                    await { player.starts.get() == 1 }
                    when (mode) {
                        "config", "media", "audio" -> {
                            val packet = if (mode == "config")
                                Message.json(Type.STREAM_CONFIG, JSONObject(), 1, 1, 1)
                            else Message(if (mode == "media") Type.VIDEO_FRAME else Type.AUDIO_PACKET,
                                session = 1, track = if (mode == "media") 1 else 2, generation = 1,
                                ptsUs = 0, payload = byteArrayOf(1))
                            Wire.write(socket.outputStream, packet)
                            assertEquals("PROTOCOL", readUntil(socket, Type.ERROR).json().string("code"))
                        }
                        "error" -> {
                            player.result = "ERROR:Media3 ERROR_CODE_DECODING_FAILED"
                            assertEquals("DECODER_FAILED", readUntil(socket, Type.ERROR).json().string("code"))
                        }
                        "disconnect" -> socket.close()
                        else -> {
                            if (mode == "late") {
                                slot.close()
                                player.result = """{"width":320,"height":240,"decoder":"late","firstFrame":true,"audioProof":true}"""
                                Thread.sleep(1100)
                            }
                            send(socket, Type.STOP, JSONObject().put("reason", "test"))
                            readUntil(socket, Type.SESSION_END)
                        }
                    }
                    await { player.closes.get() == 1 }
                    assertFalse(events.contains(ReceiverScreenEvent.FramePresented))
                }
            }
            assertEquals(1, player.closes.get())
        }
    }

    private fun assertFixtureObject(expected: JSONObject, actual: JSONObject) {
        val keys = expected.keys().asSequence().toSet()
        assertEquals(keys, actual.keys().asSequence().toSet())
        for (key in keys) {
            when (val value = expected.get(key)) {
                is JSONObject -> assertFixtureObject(value, actual.obj(key))
                is JSONArray -> assertEquals(value.toString(), actual.array(key).toString())
                else -> assertEquals(value, actual.get(key))
            }
        }
    }

    private fun settings() = checkNotNull(javaClass.getResourceAsStream("/receiver-orientation.json")).use {
        StrictJson.decode(it.readBytes()).obj("baseRequest")
    }.put("mediaTransport", JSONObject(fixture.obj("requestTransport").toString())
        .put("url", "srt://127.0.0.1:43517"))
    private fun peer(server: ReceiverServer) = Socket("127.0.0.1", server.listeningPort).apply { soTimeout = 4000 }
    private fun hello(socket: Socket) {
        Wire.write(socket.outputStream, Message.json(Type.HELLO, JSONObject().put("role", "sender")
            .put("versions", JSONArray().put(3)).put("name", "SRT fixture")))
        assertEquals(Type.HELLO, Wire.read(socket.inputStream).type)
    }
    private fun send(socket: Socket, type: Type, json: JSONObject) =
        Wire.write(socket.outputStream, Message.json(type, json, 1))
    private fun readUntil(socket: Socket, type: Type): Message {
        repeat(8) {
            val message = Wire.read(socket.inputStream)
            if (message.type == Type.PING) send(socket, Type.PONG, message.json())
            else { assertEquals(type, message.type); return message }
        }
        error("No $type")
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
}
