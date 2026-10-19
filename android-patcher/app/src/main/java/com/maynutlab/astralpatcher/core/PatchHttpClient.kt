package com.maynutlab.astralpatcher.core

import com.maynutlab.astralpatcher.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

object PatchHttpClient {
    private const val BUFFER_SIZE = 128 * 1024
    private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
    private val http = PatchHttpTransport("AstralPatchManager/${BuildConfig.VERSION_NAME}")

    fun getIndex(onEvent: (String) -> Unit = {}): String {
        TrustedUrls.requireIndex(RELEASE_INDEX_URL)
        return http.getBytes(RELEASE_INDEX_URL, MAX_METADATA_BYTES, "한글패치 배포 인덱스", onEvent).toString(Charsets.UTF_8)
    }

    fun getManifest(release: ReleaseEntry, onEvent: (String) -> Unit = {}): ByteArray =
        http.getBytes(release.manifestUrl, MAX_METADATA_BYTES, "한글패치 manifest (${release.patchVersion})", onEvent)

    /**
     * Display metadata only; never changes the selected release or its pinned manifest digest.
     * Returns an Instant-compatible UTC timestamp, or null if matching metadata has no usable
     * upload time. Network, JSON and identity-validation failures propagate to the caller.
     * Prefer the index's upload time. For older entries, use the selected manifest asset's
     * creation time. Release publication, commit and asset modification times are not upload times.
     */
    fun getPatchUploadedAt(release: ReleaseEntry): String? =
        PatchUploadTimeProtocol.lookup(release) { url ->
            http.getBytes(url, MAX_METADATA_BYTES, "한글패치 업로드 정보").toString(Charsets.UTF_8)
        }

    fun getLatestShizukuRelease(): ShizukuRelease {
        TrustedUrls.requireShizukuApi(SHIZUKU_RELEASE_API)
        val json = http.getBytes(SHIZUKU_RELEASE_API, MAX_METADATA_BYTES, "Shizuku 배포 정보").toString(Charsets.UTF_8)
        return ShizukuReleaseProtocol.parseLatest(json)
    }

    fun getLatestMobilePatcherRelease(): MobilePatcherRelease {
        TrustedUrls.requireMobilePatcherIndex(MOBILE_PATCHER_INDEX_URL)
        val json = http.getBytes(MOBILE_PATCHER_INDEX_URL, MAX_METADATA_BYTES, "Android 패처 배포 정보").toString(Charsets.UTF_8)
        return MobilePatcherReleaseProtocol.parse(json)
    }

    fun getLatestOriginalGameRelease(): OriginalGameRelease {
        TrustedUrls.requireOriginalGameIndex(ORIGINAL_GAME_INDEX_URL)
        val json = http.getBytes(ORIGINAL_GAME_INDEX_URL, MAX_METADATA_BYTES, "게임 원본 APK 배포 정보").toString(Charsets.UTF_8)
        return OriginalGameReleaseProtocol.parse(json)
    }

    fun downloadShizukuApk(
        cacheDir: File,
        release: ShizukuRelease,
        onProgress: (Int) -> Unit,
    ): File {
        TrustedUrls.requireShizukuReleaseAsset(release.downloadUrl)
        return downloadVerifiedApk(
            cacheDir,
            "shizuku-latest.apk",
            release.downloadUrl,
            release.size,
            release.sha256,
            onProgress,
        )
    }

    fun downloadMobilePatcherApk(
        cacheDir: File,
        release: MobilePatcherRelease,
        onProgress: (Int) -> Unit,
    ): File {
        TrustedUrls.requireAutoPatcherReleaseAsset(release.downloadUrl)
        return downloadVerifiedApk(
            cacheDir,
            "astral-android-patcher-${release.version}.apk",
            release.downloadUrl,
            release.size,
            release.sha256,
            onProgress,
        )
    }

    fun downloadOriginalGameApks(
        cacheDir: File,
        release: OriginalGameRelease,
        onProgress: (String, Int) -> Unit,
    ): List<File> {
        val root = File(cacheDir, "original-game/${release.versionCode}")
        root.deleteRecursively()
        require(root.mkdirs()) { "원본 게임 APK 다운로드 디렉터리를 만들 수 없습니다." }
        val totalBytes = release.files.sumOf(OriginalGameApk::size)
        var completedBytes = 0L
        val downloaded = mutableListOf<File>()
        try {
            release.files.forEach { item ->
                TrustedUrls.requireAutoPatcherReleaseAsset(item.downloadUrl)
                val target = File(root, item.name)
                require(target.parentFile == root && !target.exists()) {
                    "원본 게임 APK 다운로드 경로가 올바르지 않습니다."
                }
                http.downloadTo(target, item.downloadUrl, item.size, item.sha256, "게임 원본 APK (${item.name})", { filePercent ->
                    val currentBytes = item.size * filePercent / 100L
                    val overall = ((completedBytes + currentBytes) * 100L / totalBytes)
                        .toInt()
                        .coerceIn(0, 100)
                    onProgress(item.name, overall)
                })
                downloaded += target
                completedBytes += item.size
            }
            return downloaded
        } catch (error: Exception) {
            root.deleteRecursively()
            throw error
        }
    }

    fun downloadPayload(
        cacheDir: File,
        item: PatchFile,
        ordinal: Int,
        onProgress: (Int) -> Unit,
        onEvent: (String) -> Unit = {},
    ): File {
        val root = File(cacheDir, "patch-payloads").apply { mkdirs() }
        val transport = File(root, "$ordinal.transport")
        val payload = File(root, "$ordinal.payload")
        transport.delete()
        payload.delete()
        try {
            http.downloadTo(transport, item.downloadUrl, item.downloadSize, item.downloadSha256,
                "한글패치 리소스 ${ordinal + 1}", onProgress, onEvent)
            gunzipVerified(transport, payload, item.payloadSize, item.payloadSha256)
            return payload
        } catch (error: Exception) {
            payload.delete()
            throw error
        } finally {
            transport.delete()
        }
    }

    fun downloadOriginal(
        cacheDir: File,
        item: PatchFile,
        ordinal: Int,
        onProgress: (Int) -> Unit,
        onEvent: (String) -> Unit = {},
    ): File {
        val root = File(cacheDir, "patch-originals").apply { mkdirs() }
        val transport = File(root, "$ordinal.transport")
        val payload = File(root, "$ordinal.original")
        transport.delete()
        payload.delete()
        try {
            http.downloadTo(
                transport,
                item.sourceDownloadUrl,
                item.sourceDownloadSize,
                item.sourceDownloadSha256,
                "원본 리소스 ${ordinal + 1}",
                onProgress,
                onEvent,
            )
            gunzipVerified(transport, payload, item.sourceSize, item.sourceSha256)
            return payload
        } catch (error: Exception) {
            payload.delete()
            throw error
        } finally {
            transport.delete()
        }
    }

    private fun downloadVerifiedApk(
        cacheDir: File,
        fileName: String,
        url: String,
        size: Long,
        sha256: String,
        onProgress: (Int) -> Unit,
    ): File {
        val root = File(cacheDir, "downloads").apply { mkdirs() }
        val target = File(root, fileName)
        target.delete()
        try {
            http.downloadTo(target, url, size, sha256, fileName, onProgress)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun gunzipVerified(
        source: File,
        target: File,
        expectedSize: Long,
        expectedSha256: String,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        GZIPInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= expectedSize) { "압축 해제 결과가 manifest 크기를 초과했습니다." }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        require(total == expectedSize) { "payload 크기가 manifest와 다릅니다." }
        require(digest.digest().toHex() == expectedSha256) { "payload SHA-256이 다릅니다." }
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
