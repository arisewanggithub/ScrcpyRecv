import dev.screencopy.sender.CodecConfig
import dev.screencopy.sender.ReceiverLink
import dev.screencopy.sender.ReceiverTarget
import dev.screencopy.sender.Settings
import dev.screencopy.sender.Wire as SenderWire
import org.json.JSONObject
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.Configuration
import org.screencopy.receiver.protocol.Request
import org.screencopy.receiver.protocol.Wire as ReceiverWire
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val fixtures = Path.of(args[0])
    val cases = JSONObject(Files.readString(fixtures.resolve("v3.json"))).getJSONArray("cases")
    fun unhex(text: String) = text.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    for (index in 0 until cases.length()) {
        val case = cases.getJSONObject(index)
        val bytes = unhex(case.getString("hex"))
        var senderAccepted = false
        var receiverAccepted = false
        try {
            val message = SenderWire.read(ByteArrayInputStream(bytes))
            val output = ByteArrayOutputStream().also { SenderWire.write(it, message) }
            check(bytes.contentEquals(output.toByteArray()))
            senderAccepted = true
        } catch (_: IOException) {}
        try {
            val message = ReceiverWire.read(ByteArrayInputStream(bytes))
            val output = ByteArrayOutputStream().also { ReceiverWire.write(it, message) }
            check(bytes.contentEquals(output.toByteArray()))
            receiverAccepted = true
        } catch (_: org.screencopy.receiver.protocol.ProtocolException) {
        } catch (_: IOException) {}
        check(senderAccepted == case.getBoolean("valid") && receiverAccepted == senderAccepted) {
            "Fixture mismatch: ${case.getString("name")}"
        }
    }
    val settings = Settings().apply { audio = false }
    check(Request.parse(settings.request()).tracks == listOf(1))
    for (codec in listOf("opus", "aac", "raw")) {
        settings.audio = true
        settings.audioCodec = codec
        val csd = when (codec) {
            "opus" -> unhex(Files.readString(fixtures.resolve("opus-head.hex")))
            "aac" -> byteArrayOf(0x11, 0x90.toByte())
            else -> byteArrayOf()
        }
        val configBytes = ByteArrayOutputStream().also {
            SenderWire.write(it, SenderWire.config(1, 2, 1, CodecConfig.audio(codec, csd)))
        }.toByteArray()
        val actual = Configuration.audio(ReceiverWire.read(ByteArrayInputStream(configBytes)).json(), Request.parse(settings.request()))
        check(actual.sampleRate == 48000 && actual.channels == 2)
    }
    settings.audio = false
    val server = ReceiverServer.forProtocolTests("Cross implementation")
    val endpointPort = server.use {
        it.start()
        val target = ReceiverTarget("127.0.0.1", it.listeningPort)
        ReceiverLink(target).use { owner ->
            owner.connect()
            owner.send(SenderWire.control(SenderWire.SESSION_REQUEST, 100, settings.request()))
            check(owner.receive().type == SenderWire.SESSION_ACCEPT)
            ReceiverLink(target).use { contender ->
                contender.connect()
                contender.send(SenderWire.control(SenderWire.SESSION_REQUEST, 101, settings.request()))
                val rejection = contender.receive()
                check(rejection.type == SenderWire.REJECT && rejection.json().getString("code") == "BUSY")
            }
            owner.send(SenderWire.control(SenderWire.PING, 100, SenderWire.obj("nonce" to 123)))
            check(owner.receive().type == SenderWire.PONG)
            owner.send(SenderWire.control(SenderWire.STOP, 100, SenderWire.obj("reason" to "interop")))
            check(owner.receive().type == SenderWire.SESSION_END)
            check(target.port == it.listeningPort)
        }
        it.listeningPort
    }
    ReceiverServer.forProtocolTests("Restarted receiver", port = endpointPort).use {
        it.start()
        ReceiverLink(ReceiverTarget("127.0.0.1", endpointPort)).use { reconnected ->
            reconnected.connect()
            reconnected.send(SenderWire.control(SenderWire.SESSION_REQUEST, 102, settings.request()))
            check(reconnected.receive().type == SenderWire.SESSION_ACCEPT)
            reconnected.send(SenderWire.control(SenderWire.STOP, 102, SenderWire.obj("reason" to "restart")))
            check(reconnected.receive().type == SenderWire.SESSION_END)
        }
    }
    println("PASS: actual sender ReceiverLink/ReceiverTarget/Settings/Wire + receiver ReceiverServer over code-free v3 TCP; BUSY, internal cast IDs, ping, stop, same-endpoint restart/reconnect; ${cases.length()} shared frames; Opus/AAC/PCM config.")
}
