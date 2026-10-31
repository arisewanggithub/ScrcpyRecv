package dev.scrcpy.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class ReleaseClientTest {
    private val endpoint = "https://api.github.com/repos/example/project/releases/latest"
    private inner class Connection : HttpURLConnection(URL(endpoint)) {
        var status = 200
        var location: String? = null
        var retry: String? = null
        var length = 3L
        var wasDisconnected = false
        var input: InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        override fun disconnect() { wasDisconnected = true }
        override fun usingProxy(): Boolean = false
        override fun connect() {}
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = length
        override fun getInputStream(): InputStream = input
        override fun getHeaderField(key: String?): String? = when (key) {
            "Location" -> location
            "Retry-After" -> retry
            else -> null
        }
    }
    @Test fun successIsBoundedAndSendsPublicHeaders() {
        val connection = Connection()
        val client = ReleaseClient { connection }
        assertEquals(3, client.text(endpoint, 3).length)
        assertTrue(connection.wasDisconnected)
        assertEquals("Scrcpy-App-Updater/1", connection.getRequestProperty("User-Agent"))
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(10000, connection.connectTimeout)
        assertEquals(15000, connection.readTimeout)
    }
    @Test fun networkFailureIsNotNoUpdate() {
        for (status in listOf(500, 404, 401)) {
            val failure = Connection().apply { this.status = status }
            assertThrows(IOException::class.java) { ReleaseClient { failure }.text(endpoint, 100) }
        }
    }
    @Test fun rateLimitCarriesExplicitBoundedBackoff() {
        for (status in listOf(403, 429)) {
            val limit = Connection().apply { this.status = status; retry = "120" }
            val error = assertThrows(ReleaseClient.RateLimit::class.java) { ReleaseClient { limit }.text(endpoint, 100) }
            assertEquals(120000L, error.waitMillis)
        }
    }
    @Test fun redirectsNeverConnectToSpoofedHostAndAreBounded() {
        val redirect = Connection().apply { status = 302; location = "https://github.com.evil.example/payload" }
        val attempts = AtomicInteger()
        val client = ReleaseClient { attempts.incrementAndGet(); redirect }
        assertThrows(IllegalArgumentException::class.java) { client.text(endpoint, 100) }
        assertEquals(1, attempts.get())
        redirect.location = endpoint
        attempts.set(0)
        assertThrows(IOException::class.java) {
            ReleaseClient { attempts.incrementAndGet(); redirect }.text(endpoint, 100)
        }
        assertEquals(6, attempts.get())
    }
    @Test fun oversizeTruncatedAndCancelledTransfersFail() {
        val big = Connection()
        assertThrows(IOException::class.java) { ReleaseClient { big }.text(endpoint, 2) }
        val truncated = Connection().apply { length = 4 }
        assertThrows(IOException::class.java) { ReleaseClient { truncated }.text(endpoint, 100) }
        val unknownLength = Connection().apply { length = -1 }
        assertThrows(IOException::class.java) { ReleaseClient { unknownLength }.text(endpoint, 2) }
        val cancelled = ReleaseClient { fail("Cancelled request connected"); big }
        cancelled.cancel()
        assertThrows(CancellationException::class.java) { cancelled.text(endpoint, 100) }
    }
}
