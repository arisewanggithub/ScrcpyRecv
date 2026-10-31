package dev.scrcpy.update

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateControllerLifecycleLocalTest {
    @Test fun cancellationDisconnectsWorkerAndDropsStaleUiCallbacks() {
        val reading = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://api.github.com/fixture")) {
            override fun connect() {}
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int = 200
            override fun getContentLengthLong(): Long = -1
            override fun disconnect() { unblock.countDown() }
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    try {
                        if (!unblock.await(5, TimeUnit.SECONDS)) throw IOException("Fixture timeout")
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Fixture cancelled")
                    }
                    throw IOException("Fixture disconnected")
                }
                override fun close() { closed.countDown() }
            }
        }
        val context = RuntimeEnvironment.getApplication()
        val controller = object : UpdateController(context, "ScrcpyRecv", "ScrcpyRecv.apk") {
            override fun newClient(): ReleaseClient = ReleaseClient { connection }
        }
        val callbacks = AtomicInteger()
        controller.attach { callbacks.incrementAndGet() }
        controller.check()
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        controller.detach()
        val detachedCount = callbacks.get()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        ShadowLooper.idleMainLooper()
        assertEquals(detachedCount, callbacks.get())
        assertFalse(controller.busy)
        assertTrue(controller.status.startsWith("Cancelled"))
        assertNull(controller.release)
    }
}
