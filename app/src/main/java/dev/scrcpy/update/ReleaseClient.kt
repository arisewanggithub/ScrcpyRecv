package dev.scrcpy.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class ReleaseClient internal constructor(private val connections: Connections) {
    internal fun interface Connections { fun open(url: String): HttpURLConnection }
    constructor() : this(Connections { url ->
        URL(url).openConnection() as? HttpURLConnection ?: throw IOException("Invalid release connection")
    })
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    fun interface Progress { fun bytes(count: Long, total: Long) }
    class RateLimit internal constructor(val waitMillis: Long) :
        IOException("GitHub rate limit. Retry later (no token needed).")

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }
    private fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
    }
    private fun cooldown(c: HttpURLConnection): Long {
        val retry = c.getHeaderField("Retry-After")
        val seconds = retry?.toLongOrNull() ?: try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { isLenient = false }
            val date = retry?.let { format.parse(it) }
            date?.let { (it.time - System.currentTimeMillis()) / 1000 }
                ?: (c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.minus(System.currentTimeMillis() / 1000) ?: 3600L)
        } catch (_: Exception) {
            c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.minus(System.currentTimeMillis() / 1000) ?: 3600L
        }
        return seconds.coerceIn(60, 86400) * 1000
    }
    private fun open(initialAddress: String): HttpURLConnection {
        var address = initialAddress
        repeat(6) {
            check()
            val c = connections.open(ReleasePolicy.trustedUrl(address))
            connection = c
            try {
                check()
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "Scrcpy-App-Updater/1")
                c.setRequestProperty("Accept-Encoding", "identity")
                val status = c.responseCode
                if (status == 403 || status == 429) {
                    if (status == 403 && c.getHeaderField("Retry-After") == null &&
                        c.getHeaderField("X-RateLimit-Remaining") != "0")
                        throw IOException("Release is not publicly accessible (HTTP 403). No access token is stored.")
                    throw RateLimit(cooldown(c))
                }
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (location == null || location.length > 4096) throw IOException("Release redirect missing or too long")
                    address = URI.create(address).resolve(location).toString()
                    return@repeat
                }
                if (status != 200) throw IOException(when (status) {
                    404 -> "No public stable release is published yet."
                    401 -> "Release is not publicly accessible."
                    else -> "GitHub release request failed (HTTP $status). Retry later."
                })
                return c
            } catch (e: Exception) {
                c.disconnect()
                connection = null
                throw e
            }
        }
        throw IOException("Too many release redirects")
    }
    fun text(url: String, limit: Int): String {
        val out = ByteArrayOutputStream()
        transfer(url, out, limit.toLong(), Progress { _, _ -> })
        return out.toString(Charsets.UTF_8.name())
    }
    fun download(url: String, file: File, progress: Progress): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(FileOutputStream(file), digest).use { transfer(url, it, ReleasePolicy.APK_LIMIT, progress) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return ReleasePolicy.hex(digest.digest())
    }
    private fun transfer(url: String, out: OutputStream, limit: Long, progress: Progress) {
        val c = open(url)
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        try {
            val expected = c.contentLengthLong
            var count = 0L
            if (expected > limit) throw IOException("Release asset too large")
            requireNotNull(c.inputStream).use { input ->
                val bytes = ByteArray(16384)
                while (true) {
                    val n = input.read(bytes)
                    if (n == -1) break
                    check()
                    if (System.nanoTime() > deadline) throw IOException("Download timed out. Retry.")
                    count += n
                    if (count > limit) throw IOException("Release asset too large")
                    out.write(bytes, 0, n)
                    progress.bytes(count, expected)
                }
            }
            check()
            if (expected >= 0 && count != expected) throw IOException("Incomplete release download")
        } finally {
            c.disconnect()
            connection = null
        }
    }
}
