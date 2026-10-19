package com.maynutlab.astralpatcher.core

import org.json.JSONObject
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Upload-time metadata is descriptive and never participates in release selection or verification. */
object PatchUploadTimeProtocol {
    private const val REPOSITORY = "maynut02/astral-party-korean-patch"
    private const val DOWNLOAD_PREFIX = "/$REPOSITORY/releases/download/"
    private const val API_PREFIX = "https://api.github.com/repos/$REPOSITORY/releases/"
    private val SIMPLE_SEGMENT = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,255}")
    private val SHA256 = Regex("[0-9a-fA-F]{64}")
    private val TIMESTAMP = Regex(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}" +
            "(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?(?:Z|[+-][0-9]{2}:[0-9]{2})"
    )

    /**
     * The producer emits minute precision (e.g. 2026-10-19T09:02Z), which Instant.parse rejects.
     * Require an explicit offset and a real calendar date; never infer the device's timezone.
     */
    fun normalizeTimestamp(value: String?): String? {
        if (value == null || value.length > 128) return null
        val timestamp = value.trim()
        if (!TIMESTAMP.matches(timestamp)) return null
        return runCatching {
            OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toString()
        }.getOrNull()
    }

    /** Derive a fixed tag endpoint only from the exact trusted, selected manifest asset URL. */
    fun metadataUrl(release: ReleaseEntry): String {
        TrustedUrls.requireReleaseAsset(release.manifestUrl)
        val uri = URI(release.manifestUrl)
        require(uri.rawUserInfo == null && uri.port in setOf(-1, 443)) {
            "patch metadata URL의 authority가 올바르지 않습니다."
        }
        require(SIMPLE_SEGMENT.matches(release.patchVersion) && release.patchVersion != "latest" &&
            SIMPLE_SEGMENT.matches(release.route) && SHA256.matches(release.manifestSha256)) {
            "patch metadata의 배포 식별자가 올바르지 않습니다."
        }
        require(uri.rawPath == "$DOWNLOAD_PREFIX${release.patchVersion}/${release.route}_manifest.json") {
            "manifest URL이 선택한 배포·게임과 다릅니다."
        }
        return "${API_PREFIX}tags/${release.patchVersion}"
    }

    /**
     * Accept only a published, stable release containing exactly the selected manifest asset.
     * GitHub asset.created_at is its upload creation time. Release publication, commit time
     * and asset.updated_at (renames/edits) must never substitute for missing upload metadata.
     * Older GitHub assets can have no digest. If supplied, it must match the pinned index digest.
     */
    fun parseGitHubRelease(json: String, release: ReleaseEntry): String? {
        metadataUrl(release)
        val root = JSONObject(json)
        require(root.opt("tag_name") == release.patchVersion &&
            root.opt("html_url") == "https://github.com/$REPOSITORY/releases/tag/${release.patchVersion}" &&
            (root.opt("url") as? String)?.matches(Regex("${Regex.escape(API_PREFIX)}[1-9][0-9]*")) == true &&
            root.opt("draft") == false && root.opt("prerelease") == false) {
            "GitHub metadata가 선택한 정식 배포와 다릅니다."
        }
        val name = "${release.route}_manifest.json"
        val assets = root.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
            it.opt("name") == name || it.opt("browser_download_url") == release.manifestUrl
        }
        require(matches.size == 1) { "선택한 manifest asset을 유일하게 확인할 수 없습니다." }
        val asset = matches.single()
        require(asset.opt("name") == name && asset.opt("browser_download_url") == release.manifestUrl &&
            asset.opt("state") == "uploaded") { "선택한 manifest asset과 metadata가 다릅니다." }
        if (!asset.isNull("digest")) {
            val digest = asset.getString("digest")
            require(digest.equals("sha256:${release.manifestSha256}", ignoreCase = true)) {
                "manifest asset SHA-256이 선택한 배포와 다릅니다."
            }
        }
        return normalizeTimestamp(asset.opt("created_at") as? String)
    }

    /**
     * No latest lookup, release substitution, mutable release entry, or fabricated time.
     * Null means verified matching metadata lacks a usable upload field. Fetch/validation
     * failures propagate so the UI can distinguish an absent time from a failed lookup.
     */
    internal fun lookup(release: ReleaseEntry, fetchMetadata: (String) -> String): String? {
        normalizeTimestamp(release.uploadedAt)?.let { return it }
        return parseGitHubRelease(fetchMetadata(metadataUrl(release)), release)
    }
}
