package dev.scrcpy.releasecheck

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeSrtCheck {
    private val api = SrtApi(TargetApp())
    private val stream = "sc-0000000000000001"

    @Test fun exactNativeBytesAcrossOffsetsMessagesAndFreshConnections() {
        assertEquals("1.5.7", api.version())
        repeat(2) {
            connected { listener, caller ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val expected = ByteArray(96 * 1024 + 17) { (it * 31).toByte() }
                    val receiving = executor.submit(Callable {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(1009)
                        while (output.size() < expected.size) {
                            val count = api.read(caller, buffer, 7, minOf(997, expected.size - output.size()))
                            check(count > 0) { "Unexpected native EOF" }
                            output.write(buffer, 7, count)
                        }
                        output.toByteArray()
                    })
                    val padded = ByteArray(expected.size + 18)
                    expected.copyInto(padded, 9)
                    api.write(listener, padded, 9, expected.size)
                    assertArrayEquals(expected, receiving.get(8, TimeUnit.SECONDS))
                } finally {
                    api.close(caller)
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun nativeCloseCancelsAcceptConnectReadAndBackpressuredWrite() {
        cancel(api.listen(stream)) { api.accept(it, 10000) }
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { silent ->
            cancel(api.caller(stream)) { api.connect(it, silent.localPort, 10000) }
        }
        connected { _, caller -> cancel(caller) { api.read(it, ByteArray(128), 0, 128) } }
        connected { listener, _ ->
            cancel(listener) {
                val payload = ByteArray(8 * 1024 * 1024)
                while (true) api.write(it, payload, 0, payload.size)
            }
        }
    }

    @Test fun wrongNativeStreamIdAndStaleHandlesAreRejected() {
        val listener = api.listen(stream)
        val caller = api.caller("sc-0000000000000002")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val connecting = executor.submit(Callable { api.connect(caller, api.port(listener), 3000) })
            assertThrows(IOException::class.java) { api.accept(listener, 3000) }
            val error = assertThrows(ExecutionException::class.java) { connecting.get(5, TimeUnit.SECONDS) }
            assertTrue(error.cause is IOException)
        } finally {
            api.close(caller); api.close(listener)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
        }
        assertThrows(IOException::class.java) { api.read(listener, ByteArray(1), 0, 1) }
        assertTrue(api.snapshot(listener).contains("CLOSED"))
        api.close(listener)
    }

    private fun connected(action: (Long, Long) -> Unit) {
        val listener = api.listen(stream)
        val caller = api.caller(stream)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val connecting = executor.submit(Callable { api.connect(caller, api.port(listener), 3000) })
            assertTrue(api.accept(listener, 3000))
            connecting.get(5, TimeUnit.SECONDS)
            action(listener, caller)
        } finally {
            api.close(caller); api.close(listener)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
        }
    }

    private fun cancel(handle: Long, operation: (Long) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val pending = executor.submit(Callable {
                entered.countDown()
                try { operation(handle); false } catch (_: IOException) { true }
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertFalse("Native operation completed before close", pending.isDone)
            api.close(handle)
            assertTrue("Native close did not cancel blocked work", pending.get(3, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { api.read(handle, ByteArray(1), 0, 1) }
        } finally {
            api.close(handle)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
        }
    }
}
