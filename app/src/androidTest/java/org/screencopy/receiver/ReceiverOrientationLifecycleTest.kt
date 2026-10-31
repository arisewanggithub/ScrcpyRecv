package org.screencopy.receiver

import android.content.res.Configuration
import android.view.SurfaceView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.screencopy.receiver.lifecycle.ReceiverWork
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.Message
import org.screencopy.receiver.protocol.ReceiverOrientation
import org.screencopy.receiver.protocol.StrictJson
import org.screencopy.receiver.protocol.Type
import org.screencopy.receiver.protocol.Wire
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ReceiverOrientationLifecycleTest {
    @Test fun presentationAndConfigurationKeepTheActivitySurfaceAndAcceptedTransport() {
        requireVisibleDevice()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitSurface(scenario)
            lateinit var originalActivity: MainActivity
            lateinit var originalSurface: SurfaceView
            var originalPolicy = 0
            scenario.onActivity {
                originalActivity = it
                originalSurface = it.findViewById(R.id.video_surface)
                originalPolicy = it.requestedOrientation
            }
            Socket("127.0.0.1", ReceiverServer.PORT).use { peer ->
                peer.soTimeout = 4000
                Wire.write(peer.outputStream, Message.json(Type.HELLO,
                    JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Orientation test")))
                assertEquals(Type.HELLO, read(peer).type)
                val request = StrictJson.decode(
                    """{"video":{"codec":"h264","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""},"receiverOrientation":"landscape"}""".toByteArray())
                Wire.write(peer.outputStream, Message.json(Type.SESSION_REQUEST, request, 47))
                assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                lateinit var lease: AutoCloseable
                scenario.onActivity {
                    assertEquals("Negotiation must not change orientation", originalPolicy, it.requestedOrientation)
                    // Enter the same UI boundary used by the server, without creating media/capture resources.
                    lease = it.beginPresentation(ReceiverOrientation.LANDSCAPE)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                awaitSurface(scenario)
                scenario.onActivity {
                    assertSame(originalActivity, it)
                    assertSame(originalSurface, it.findViewById(R.id.video_surface))
                    it.onConfigurationChanged(Configuration(it.resources.configuration))
                    assertSame(originalSurface, it.findViewById(R.id.video_surface))
                }
                Wire.write(peer.outputStream, Message.json(Type.PING, JSONObject().put("nonce", 61), 47))
                val response = read(peer)
                assertEquals(Type.PONG, response.type)
                assertEquals(61, response.json().getInt("nonce"))
                lease.close()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { assertEquals(originalPolicy, it.requestedOrientation) }
                Wire.write(peer.outputStream, Message.json(Type.STOP, JSONObject().put("reason", "orientation test"), 47))
                assertEquals(Type.SESSION_END, read(peer).type)
            }
        }
        ReceiverWork.executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    @Test fun genuineActivityRecreationRestoresPolicyRatherThanLeakingIntoAutomaticMode() {
        requireVisibleDevice()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitSurface(scenario)
            var original = 0
            scenario.onActivity {
                original = it.requestedOrientation
                it.beginPresentation(ReceiverOrientation.PORTRAIT)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.recreate()
            awaitSurface(scenario)
            scenario.onActivity { assertEquals(original, it.requestedOrientation) }
        }
        ReceiverWork.executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun requireVisibleDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = checkNotNull(context.getSystemService(android.os.PowerManager::class.java))
        val keyguard = checkNotNull(context.getSystemService(android.app.KeyguardManager::class.java))
        assertTrue("An explicitly authorized awake/unlocked receiver is required for orientation UI testing",
            power.isInteractive && !keyguard.isKeyguardLocked)
    }

    private fun read(peer: Socket): Message {
        repeat(8) {
            val message = Wire.read(peer.inputStream)
            if (message.type != Type.PING) return message
            Wire.write(peer.outputStream, Message.json(Type.PONG, message.json(), message.session))
        }
        throw AssertionError("Expected a non-heartbeat response")
    }

    private fun awaitSurface(scenario: ActivityScenario<MainActivity>) {
        val ready = AtomicBoolean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready.get() && System.nanoTime() < deadline) {
            scenario.onActivity {
                ready.set(it.findViewById<SurfaceView>(R.id.video_surface).holder.surface.isValid)
            }
            if (!ready.get()) Thread.sleep(10)
        }
        assertTrue("Receiver Surface must be valid", ready.get())
        ReceiverWork.executor.submit {}.get(5, TimeUnit.SECONDS)
    }
}
