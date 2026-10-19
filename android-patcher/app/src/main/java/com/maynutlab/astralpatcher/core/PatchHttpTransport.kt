package com.maynutlab.astralpatcher.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

/** GET-only transport. Every retry starts at the original URL, including its redirect chain. */
internal class PatchHttpTransport(
    private val userAgent: String,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun getBytes(url: String, maxBytes: Int, label: String, onEvent: (String) -> Unit = {}): ByteArray =
        request(url, label, "application/json", onEvent) { connection ->
            val declared = connection.contentLengthLong
            require(declared <= 0 || declared <= maxBytes) { "metadata가 허용 크기를 초과합니다." }
            network(connection.url.host) { BufferedInputStream(connection.inputStream) }.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    checkCancellation()
                    val read = network(connection.url.host) { input.read(buffer) }
                    if (read < 0) break
                    require(read <= maxBytes - output.size()) { "metadata가 허용 크기를 초과합니다." }
                    output.write(buffer, 0, read)
                }
                if (declared > 0 && output.size().toLong() != declared) {
                    throw TransferFailure("metadata 전송이 중단되었습니다 (${output.size()}/$declared bytes).", true)
                }
                output.toByteArray()
            }
        }

    fun downloadTo(
        target: File,
        url: String,
        expectedSize: Long,
        expectedSha256: String,
        label: String,
        onProgress: (Int) -> Unit,
        onEvent: (String) -> Unit = {},
    ) {
        require(expectedSize > 0)
        var verified = false
        try {
            request(url, label, "application/octet-stream", onEvent) { connection ->
                val declared = connection.contentLengthLong
                require(declared <= 0 || declared == expectedSize) { "다운로드 크기가 manifest와 다릅니다." }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                network(connection.url.host) { BufferedInputStream(connection.inputStream) }.use { input ->
                    // Truncate for every attempt; a partial response is never resumed or appended.
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            checkCancellation()
                            val read = network(connection.url.host) { input.read(buffer) }
                            if (read < 0) break
                            require(read <= expectedSize - total) { "다운로드가 manifest 크기를 초과했습니다." }
                            total += read
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            onProgress((total.toDouble() / expectedSize * 100).toInt().coerceIn(0, 100))
                        }
                        output.fd.sync()
                    }
                }
                if (total != expectedSize) {
                    throw TransferFailure("파일 전송이 중단되었습니다 ($total/$expectedSize bytes).", true)
                }
                require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == expectedSha256) {
                    "다운로드 SHA-256이 다릅니다."
                }
            }
            verified = true
        } finally {
            if (!verified) target.delete()
        }
    }

    private fun <T> request(
        initial: String,
        label: String,
        accept: String,
        onEvent: (String) -> Unit,
        read: (HttpURLConnection) -> T,
    ): T {
        require(URL(initial).protocol == "https") { "HTTPS URL만 사용할 수 있습니다." }
        for (attempt in 1..MAX_ATTEMPTS) {
            checkCancellation()
            onEvent("[HTTP] $label · GET ${publicUrl(initial)} · 시도 $attempt/$MAX_ATTEMPTS")
            var retryDelay: Long? = null
            try {
                val connection = open(initial, accept, label, onEvent)
                try {
                    val result = read(connection)
                    onEvent("[HTTP] $label · 수신 완료 · 시도 $attempt/$MAX_ATTEMPTS")
                    return result
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                checkCancellation()
                if (error is InterruptedIOException) throw error
                val failure = error as? TransferFailure
                val detail = failure?.message ?: error.message ?: error.javaClass.simpleName
                val delay = failure?.retryAfterMs ?: (1_000L shl (attempt - 1))
                if (failure?.retryable == true && attempt < MAX_ATTEMPTS && delay <= MAX_RETRY_DELAY_MS) {
                    onEvent("[HTTP] $label · $detail · ${delay}ms 후 재시도 ${attempt + 1}/$MAX_ATTEMPTS")
                    retryDelay = delay
                } else {
                    val waiting = if (delay > MAX_RETRY_DELAY_MS) " 서버가 더 긴 대기를 요청했습니다. 잠시 후 다시 시도해 주세요." else ""
                    val message = "$label 다운로드 실패: $detail (시도 $attempt/$MAX_ATTEMPTS).$waiting"
                    onEvent("[HTTP] $message")
                    // Network exceptions may contain signed CDN query strings; expose only sanitized details.
                    throw IOException(message)
                }
            }
            try {
                pause(requireNotNull(retryDelay))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                checkCancellation()
            }
        }
        error("다운로드 재시도 횟수를 초과했습니다.")
    }

    private fun open(initial: String, accept: String, label: String, onEvent: (String) -> Unit): HttpURLConnection {
        var current = initial
        for (redirect in 0..MAX_REDIRECTS) {
            checkCancellation()
            val connection = network(URL(current).host) { connect(URL(current)) }
            var retained = false
            try {
                connection.apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", accept)
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val status = network(connection.url.host) { connection.responseCode }
                val requestId = connection.getHeaderField("X-GitHub-Request-Id")
                    ?.takeIf { it.matches(Regex("[a-zA-Z0-9:-]{1,120}")) }
                onEvent("[HTTP] $label · ${connection.url.host} · HTTP $status${requestId?.let { " · requestId=$it" }.orEmpty()}")
                if (status in 200..299) {
                    retained = true
                    return connection
                }
                if (status in REDIRECT_STATUSES) {
                    require(redirect < MAX_REDIRECTS) { "redirect 횟수가 허용 범위를 초과했습니다." }
                    val location = requireNotNull(connection.getHeaderField("Location")) { "redirect 응답에 Location이 없습니다." }
                    val next = URL(URL(current), location).toString()
                    require(TrustedUrls.isTrustedRedirect(next)) { "신뢰하지 않는 redirect입니다." }
                    current = next
                } else {
                    throw TransferFailure(
                        "HTTP $status (${connection.url.host})",
                        status in RETRY_STATUSES,
                        retryAfter(connection.getHeaderField("Retry-After")),
                    )
                }
            } finally {
                if (!retained) connection.disconnect()
            }
        }
        error("redirect 횟수가 허용 범위를 초과했습니다.")
    }

    private fun retryAfter(value: String?): Long? {
        val text = value?.trim() ?: return null
        if (text.matches(Regex("[0-9]+"))) {
            val seconds = text.toLongOrNull() ?: Long.MAX_VALUE
            return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
        }
        return runCatching {
            (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now()).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun <T> network(host: String, action: () -> T): T = try {
        action()
    } catch (error: IOException) {
        checkCancellation()
        throw TransferFailure("네트워크 오류 ${error.javaClass.simpleName} ($host)", error !is SSLException)
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("다운로드가 취소되었습니다.")
    }

    private fun publicUrl(value: String): String = URL(value).let { "${it.protocol}://${it.host}${it.path}" }

    private class TransferFailure(message: String, val retryable: Boolean, val retryAfterMs: Long? = null) : IOException(message)

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val MAX_REDIRECTS = 5
        private const val MAX_RETRY_DELAY_MS = 15_000L
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val RETRY_STATUSES = setOf(408, 429, 500, 502, 503, 504)
    }
}
