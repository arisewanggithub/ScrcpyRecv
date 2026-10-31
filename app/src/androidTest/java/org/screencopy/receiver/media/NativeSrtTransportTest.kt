package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.screencopy.receiver.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Transport-only loopback: no capture, Activity, permissions or external endpoints. */
class NativeSrtTransportTest {
    private val stream = "sc-0000000000000001"

    @Test fun connectedAndClosedSnapshotsExposeOnlyTransportStateWithoutMaskingReadFailure() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        connected { sender, caller ->
            val stats = SrtFailureDetails.snapshot(SrtNative.snapshot(sender))
            assertEquals("available", stats.getString("snapshot"))
            assertEquals("5", stats.getString("state"))
            assertTrue(stats.has("packets_sent"))
            assertTrue(stats.has("acknowledgements_received"))
            assertTrue(stats.has("send_buffer_packets"))
            assertTrue(stats.has("send_buffer_bytes"))
            assertTrue(stats.has("receive_buffer_bytes"))
            assertEquals("unavailable", stats.getString("last_peer_response_ms"))
            SrtNative.close(caller)
            val error = assertThrows(IOException::class.java) { SrtNative.read(caller, ByteArray(1), 0, 1) }
            val detail = SrtFailureDetails.describe(error)
            assertEquals("CLOSED", detail.getString("category"))
            assertEquals("closed", detail.getJSONObject("native_snapshot").getString("snapshot_unavailable"))
            val closed = SrtFailureDetails.snapshot(SrtNative.snapshot(caller))
            assertEquals("unavailable", closed.getString("snapshot"))
            assertEquals("closed", closed.getString("snapshot_unavailable"))
        }
    }

    @Test fun remoteShutdownRetainsNativeReadErrorAndItsPreCloseSnapshot() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        connected { sender, caller ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit(Callable {
                    assertThrows(IOException::class.java) { SrtNative.read(caller, ByteArray(1), 0, 1) }
                })
                SrtNative.close(sender)
                val error = result.get(8, TimeUnit.SECONDS)
                val detail = SrtFailureDetails.describe(error)
                assertEquals("BROKEN", detail.getString("category"))
                assertEquals("RECEIVE", detail.getString("operation"))
                assertEquals(2001, detail.getInt("native_code"))
                assertTrue(detail.getJSONObject("native_snapshot").has("state"))
                assertEquals("unavailable", detail.getJSONObject("native_snapshot").getString("last_peer_response_ms"))
            } finally {
                SrtNative.close(caller)
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun exactBytesAcrossMessagesOffsetsAndFreshConnections() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        assertEquals("1.5.7", SrtNative.version())
        repeat(2) {
            connected { listener, caller ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val expected = ByteArray(96 * 1024 + 17) { index -> (index * 31).toByte() }
                    val receiving = executor.submit(Callable {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(1009)
                        while (output.size() < expected.size) {
                            val count = SrtNative.read(caller, buffer, 7,
                                minOf(997, expected.size - output.size()))
                            if (count < 1) throw IOException("EOF before complete loopback payload")
                            output.write(buffer, 7, count)
                        }
                        output.toByteArray()
                    })
                    val padded = ByteArray(expected.size + 18)
                    expected.copyInto(padded, 9)
                    SrtNative.write(listener, padded, 9, expected.size)
                    assertArrayEquals(expected, receiving.get(5, TimeUnit.SECONDS))
                } finally {
                    SrtNative.close(caller)
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun closeCancelsAcceptConnectReadAndBackpressuredWrite() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val listener = SrtNative.listen("127.0.0.1", "127.0.0.1", stream, 100)
        cancelOperation(listener) { SrtNative.awaitCaller(listener, 10000) }

        val connecting = SrtNative.createCaller(stream, 100)
        // A loopback UDP socket deliberately does not implement SRT; no LAN probing.
        java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            cancelOperation(connecting) {
                SrtNative.connect(connecting, "127.0.0.1", silent.localPort, 10000)
            }
        }
        connected { _, caller ->
            cancelOperation(caller) { SrtNative.read(caller, ByteArray(128), 0, 128) }
        }
        connected { sender, _ ->
            cancelOperation(sender) {
                val payload = ByteArray(8 * 1024 * 1024)
                while (true) SrtNative.write(sender, payload, 0, payload.size)
            }
        }
    }

    @Test fun rejectsCallerWhoseStreamIdDoesNotMatchControlSession() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        connected(expectedStream = "sc-0000000000000002", rejected = true) { _, _ ->
            fail("Mismatched stream ID was accepted")
        }
    }

    private fun cancelOperation(handle: Long, operation: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val pending = executor.submit(Callable {
                entered.countDown()
                try {
                    operation()
                    false
                } catch (_: IOException) {
                    true
                }
            })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse("Operation finished before cancellation was exercised", pending.isDone)
            val start = System.nanoTime()
            SrtNative.close(handle)
            assertTrue("Cancellation did not surface a closed transport", pending.get(2, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
            assertThrows(IOException::class.java) {
                SrtNative.read(handle, ByteArray(1), 0, 1)
            }
            SrtNative.close(handle)
        } finally {
            SrtNative.close(handle)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun connected(
        expectedStream: String = stream,
        rejected: Boolean = false,
        action: (Long, Long) -> Unit
    ) {
        val listener = SrtNative.listen("127.0.0.1", "127.0.0.1", expectedStream, 100)
        val caller = SrtNative.createCaller(stream, 100)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val connecting = executor.submit(Callable {
                SrtNative.connect(caller, "127.0.0.1", SrtNative.localPort(listener), 3000)
            })
            if (rejected) {
                assertThrows(IOException::class.java) { SrtNative.awaitCaller(listener, 3000) }
            } else {
                assertTrue(SrtNative.awaitCaller(listener, 3000))
                connecting.get(4, TimeUnit.SECONDS)
                action(listener, caller)
            }
        } finally {
            SrtNative.close(caller)
            SrtNative.close(listener)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
        }
    }
}
