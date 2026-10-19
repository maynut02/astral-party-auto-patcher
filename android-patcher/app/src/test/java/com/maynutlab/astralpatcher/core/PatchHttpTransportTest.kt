package com.maynutlab.astralpatcher.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

class PatchHttpTransportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retriesExpiredCdnFromOriginalUrlWithoutLeakingSignedQueries() {
        val first = "$CDN?sig=expired-secret"
        val fresh = "$CDN?sig=fresh-secret"
        val h = Harness(
            reply(302, headers = mapOf("Location" to first)),
            reply(502, url = first),
            reply(302, headers = mapOf("Location" to fresh)),
            reply(200, url = fresh),
        )
        assertArrayEquals(DATA, h.get())
        assertEquals(listOf(ORIGINAL, first, ORIGINAL, fresh), h.urls)
        assertEquals(listOf(1_000L), h.delays)
        assertTrue(h.events.any { "HTTP 502" in it })
        h.assertPrivateAndClosed("expired-secret", "fresh-secret", "sig=")
    }

    @Test fun stopsAfterThreeConsecutive502Responses() {
        val h = Harness(*Array(3) { reply(502) })
        val error = assertThrows(IOException::class.java) { h.get() }
        assertTrue(error.message.orEmpty().contains("502"))
        assertEquals(List(3) { ORIGINAL }, h.urls)
        assertEquals(listOf(1_000L, 2_000L), h.delays)
        h.assertPrivateAndClosed()
    }

    @Test fun retriesTransientHttpStatuses() {
        for (status in listOf(408, 429, 500, 503, 504)) {
            val h = Harness(reply(status), reply(200))
            assertArrayEquals("HTTP $status", DATA, h.get())
            assertEquals(listOf(ORIGINAL, ORIGINAL), h.urls)
            assertEquals(listOf(1_000L), h.delays)
            h.assertPrivateAndClosed()
        }
    }

    @Test fun neverRetriesForbiddenOrMissingAssets() {
        for (status in listOf(403, 404)) {
            val h = Harness(reply(status))
            val error = assertThrows(IOException::class.java) { h.get() }
            assertTrue(error.message.orEmpty().contains("HTTP $status"))
            assertEquals(1, h.urls.size)
            assertTrue(h.delays.isEmpty())
            h.assertPrivateAndClosed()
        }
    }

    @Test fun honorsRetryAfterSecondsIncludingZeroAndMaximumAllowedWait() {
        for (seconds in listOf(0, 3, 15)) {
            val h = Harness(reply(429, headers = mapOf("Retry-After" to " $seconds ")), reply(200))
            assertArrayEquals(DATA, h.get())
            assertEquals(listOf(seconds * 1_000L), h.delays)
            h.assertPrivateAndClosed()
        }
    }

    @Test fun honorsRetryAfterHttpDatesAndClampsPastDatesToZero() {
        for (offset in listOf(-5_000L, 7_000L)) {
            val h = Harness(reply(503, headers = mapOf("Retry-After" to httpDate(NOW + offset))), reply(200))
            assertArrayEquals(DATA, h.get())
            assertEquals(listOf(offset.coerceAtLeast(0)), h.delays)
            h.assertPrivateAndClosed()
        }
    }

    @Test fun refusesExcessiveRetryAfterInsteadOfSleepingOrOverflowing() {
        for (value in listOf("16", "9999999999999999999999999999", httpDate(NOW + 16_000))) {
            val h = Harness(reply(429, headers = mapOf("Retry-After" to value)))
            assertThrows(IOException::class.java) { h.get() }
            assertEquals(1, h.urls.size)
            assertTrue(h.delays.isEmpty())
            h.assertPrivateAndClosed()
        }
    }

    @Test fun malformedRetryAfterUsesBoundedDefaultBackoff() {
        for (value in listOf("tomorrow", "-3")) {
            val h = Harness(reply(503, headers = mapOf("Retry-After" to value)), reply(200))
            assertArrayEquals(DATA, h.get())
            assertEquals(listOf(1_000L), h.delays)
            h.assertPrivateAndClosed()
        }
    }

    @Test fun partialReadIOExceptionRestartsFileInsteadOfAppending() {
        val file = temporary.newFile().apply { writeText("stale-longer-file-content") }
        val h = Harness(reply(200, stream = ProbeStream(DATA, failAfter = 2)), reply(200))
        h.onPause = { assertArrayEquals(DATA.copyOf(2), file.readBytes()) }
        h.download(file)
        assertArrayEquals(DATA, file.readBytes())
        assertEquals(2, h.urls.size)
        assertEquals(100, h.progress.last())
        h.assertPrivateAndClosed()
    }

    @Test fun prematureEofRetriesDownloadFromByteZero() {
        val file = temporary.newFile()
        val h = Harness(reply(200, stream = ProbeStream(DATA.copyOf(2))), reply(200))
        h.download(file)
        assertArrayEquals(DATA, file.readBytes())
        assertEquals(listOf(1_000L), h.delays)
        h.assertPrivateAndClosed()
    }

    @Test fun exhaustedPartialTransfersDeleteTargetIncludingPreexistingBytes() {
        for (throwsIo in listOf(false, true)) {
            val file = temporary.newFile().apply { writeText("stale") }
            val h = Harness(*Array(3) {
                reply(200, stream = if (throwsIo) ProbeStream(DATA, failAfter = 2) else ProbeStream(DATA.copyOf(2)))
            })
            assertThrows(IOException::class.java) { h.download(file) }
            assertFalse(file.exists())
            assertEquals(3, h.urls.size)
            assertEquals(listOf(1_000L, 2_000L), h.delays)
            h.assertPrivateAndClosed()
        }
    }

    @Test fun metadataPrematureEofRetriesWithoutReturningTruncatedJson() {
        val h = Harness(reply(200, stream = ProbeStream(DATA.copyOf(2))), reply(200))
        assertArrayEquals(DATA, h.get())
        assertEquals(2, h.urls.size)
        h.assertPrivateAndClosed()
    }

    @Test fun metadataReadIOExceptionRetriesWithoutCombiningAttempts() {
        val h = Harness(reply(200, stream = ProbeStream(DATA, failAfter = 2)), reply(200))
        assertArrayEquals(DATA, h.get())
        assertEquals(2, h.urls.size)
        h.assertPrivateAndClosed()
    }

    @Test fun wrongHashDoesNotRetryAndDeletesUntrustedFile() {
        val file = temporary.newFile()
        val h = Harness(reply(200))
        assertThrows(IOException::class.java) { h.download(file, sha = "0".repeat(64)) }
        assertFalse(file.exists())
        assertEquals(1, h.urls.size)
        assertTrue(h.delays.isEmpty())
        h.assertPrivateAndClosed()
    }

    @Test fun streamingPayloadOverflowDoesNotRetryAndDeletesFile() {
        val file = temporary.newFile()
        val h = Harness(reply(200, length = -1, stream = ProbeStream(DATA + byteArrayOf(1))))
        assertThrows(IOException::class.java) { h.download(file) }
        assertFalse(file.exists())
        assertEquals(1, h.urls.size)
        assertTrue(h.delays.isEmpty())
        h.assertPrivateAndClosed()
    }

    @Test fun declaredPayloadSizeMismatchFailsBeforeReadingOrWriting() {
        for (length in listOf(DATA.size - 1L, DATA.size + 1L)) {
            val file = temporary.newFile().apply { writeText("old") }
            val response = reply(200, length = length)
            val h = Harness(response)
            assertThrows(IOException::class.java) { h.download(file) }
            assertEquals(0, response.stream.bytesRead)
            assertFalse(file.exists())
            assertEquals(1, h.urls.size)
            assertTrue(h.delays.isEmpty())
            h.assertPrivateAndClosed()
        }
    }

    @Test fun declaredAndStreamingMetadataOverflowNeverRetry() {
        for (length in listOf(DATA.size.toLong(), -1L)) {
            val response = reply(200, length = length)
            val h = Harness(response)
            assertThrows(IOException::class.java) { h.get(maxBytes = DATA.size - 1) }
            if (length > 0) assertEquals(0, response.stream.bytesRead)
            assertEquals(1, h.urls.size)
            assertTrue(h.delays.isEmpty())
            h.assertPrivateAndClosed()
        }
    }

    @Test fun sslFailuresAreNotRetriedAndNeverExposeExceptionUrls() {
        for (duringRead in listOf(false, true)) {
            val secret = "tls-private-token"
            val error = SSLException("certificate failed at $CDN?sig=$secret")
            val response = if (duringRead) reply(200, stream = ProbeStream(DATA, 0, error))
                else reply(200, responseFailure = error)
            val h = Harness(response)
            val failure = assertThrows(IOException::class.java) { h.get() }
            assertFalse(failure.toString().contains(secret))
            assertEquals(1, h.urls.size)
            assertTrue(h.delays.isEmpty())
            h.assertPrivateAndClosed(secret)
        }
    }

    @Test fun unsafeRedirectsFailBeforeContactingDestinationWithoutRetry() {
        for (destination in listOf("http://release-assets.githubusercontent.com/a", "https://evil.example/a", "https://github.com.evil.example/a")) {
            val h = Harness(reply(302, headers = mapOf("Location" to destination)))
            assertThrows(IOException::class.java) { h.get() }
            assertEquals(listOf(ORIGINAL), h.urls)
            assertTrue(h.delays.isEmpty())
            h.assertPrivateAndClosed()
        }
    }

    @Test fun interruptedBackoffCancelsWithoutNextConnectionAndDeletesPartialFile() {
        val file = temporary.newFile()
        val h = Harness(reply(200, stream = ProbeStream(DATA, failAfter = 2)))
        h.onPause = { throw InterruptedException("cancel") }
        try {
            assertThrows(InterruptedIOException::class.java) { h.download(file) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(file.exists())
            assertEquals(1, h.urls.size)
            assertEquals(listOf(1_000L), h.delays)
            h.assertPrivateAndClosed()
        } finally {
            // Transport preserves cancellation; the test must not poison the JVM worker.
            Thread.interrupted()
        }
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun alreadyInterruptedDownloadDoesNoNetworkAndCleansTarget() {
        val file = temporary.newFile().apply { writeText("stale") }
        val h = Harness()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) { h.download(file) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(h.urls.isEmpty())
            assertTrue(h.delays.isEmpty())
            assertFalse(file.exists())
        } finally {
            Thread.interrupted()
        }
        assertFalse(Thread.currentThread().isInterrupted)
    }

    private class Harness(vararg responses: FakeConnection) {
        private val script = responses.toList()
        val urls = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        val events = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        var onPause: () -> Unit = {}
        private val transport = PatchHttpTransport(
            userAgent = "transport-jvm-test",
            connect = { url ->
                val connection = script.getOrNull(urls.size) ?: throw AssertionError("Unexpected connection: ${url.host}")
                assertEquals(connection.url.toString(), url.toString())
                urls += url.toString()
                connection
            },
            pause = { delay -> delays += delay; onPause() },
            now = { NOW },
        )

        fun get(maxBytes: Int = 1024): ByteArray = transport.getBytes(ORIGINAL, maxBytes, "원본", events::add)

        fun download(file: File, sha: String = sha256(DATA)) = transport.downloadTo(
            file, ORIGINAL, DATA.size.toLong(), sha, "원본", progress::add, events::add,
        )

        fun assertPrivateAndClosed(vararg secrets: String) {
            assertEquals("Unused scripted responses", script.size, urls.size)
            script.forEach { response ->
                assertEquals("Connection must be disconnected exactly once", 1, response.disconnects)
                if (response.inputOpened) assertTrue("Response stream must close", response.stream.closed)
            }
            for (secret in secrets) assertFalse("Sensitive URL leaked", events.any { secret in it })
        }
    }

    private class FakeConnection(
        url: String,
        private val status: Int,
        private val length: Long,
        private val headers: Map<String, String>,
        val stream: ProbeStream,
        private val responseFailure: IOException?,
    ) : HttpURLConnection(URL(url)) {
        var disconnects = 0
        var inputOpened = false
        override fun connect() = Unit
        override fun disconnect() { disconnects++ }
        override fun usingProxy() = false
        override fun getResponseCode(): Int { responseFailure?.let { throw it }; return status }
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getInputStream(): InputStream { inputOpened = true; return stream }
    }

    private class ProbeStream(
        private val bytes: ByteArray,
        private val failAfter: Int? = null,
        private val failure: IOException = IOException("connection dropped"),
    ) : InputStream() {
        var bytesRead = 0
        var closed = false
        override fun read(): Int {
            if (failAfter != null && bytesRead >= failAfter) throw failure
            if (bytesRead == bytes.size) return -1
            return bytes[bytesRead++].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (failAfter != null && bytesRead >= failAfter) throw failure
            if (bytesRead == bytes.size) return -1
            val count = minOf(length, bytes.size - bytesRead, (failAfter ?: bytes.size) - bytesRead)
            bytes.copyInto(buffer, offset, bytesRead, bytesRead + count)
            bytesRead += count
            return count
        }
        override fun close() { closed = true }
    }

    companion object {
        private const val ORIGINAL = "https://github.com/maynut02/astral-party-korean-patch/releases/download/v3.3.0_r026-original/resource.bin.gz"
        private const val CDN = "https://release-assets.githubusercontent.com/github-production-release-asset/resource"
        private val DATA = "abcdef".toByteArray()
        private val NOW = Instant.parse("2026-10-19T13:43:00Z").toEpochMilli()

        private fun reply(
            status: Int,
            url: String = ORIGINAL,
            length: Long = DATA.size.toLong(),
            headers: Map<String, String> = emptyMap(),
            stream: ProbeStream = ProbeStream(DATA),
            responseFailure: IOException? = null,
        ) = FakeConnection(url, status, length, headers, stream, responseFailure)

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun httpDate(epochMillis: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))
    }
}
