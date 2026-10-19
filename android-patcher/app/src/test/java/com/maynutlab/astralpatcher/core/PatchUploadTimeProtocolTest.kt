package com.maynutlab.astralpatcher.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.TimeZone

class PatchUploadTimeProtocolTest {
    private val release = ReleaseEntry(
        route = "INT_ANDROID",
        gameVersion = "3.3.0",
        revision = "026",
        catalogHash = "b686dea4b47e3896f03aaabbe8caf988",
        patchVersion = "v3.3.0_r026_p0",
        manifestUrl = "$DOWNLOAD/v3.3.0_r026_p0/INT_ANDROID_manifest.json",
        manifestSha256 = "2003bb96aa02d11daab82c228140e9cc54ec20c8107277c04ac593a93d3695e9",
    )

    @Test
    fun normalizesActualProducerMinutePrecisionIntoAnInstant() {
        // builder/automation/release.py uses isoformat(timespec="minutes").
        val raw = "2026-10-19T09:02Z"
        assertThrows(DateTimeParseException::class.java) { Instant.parse(raw) }
        val normalized = PatchUploadTimeProtocol.normalizeTimestamp(raw)
        assertEquals("2026-10-19T09:02:00Z", normalized)
        assertEquals(Instant.parse("2026-10-19T09:02:00Z"), Instant.parse(normalized))
    }

    @Test
    fun supportsSecondsFractionsAndExplicitOffsetsWithoutChangingTheInstant() {
        for ((input, expected) in mapOf(
            "2026-10-19T09:02:24Z" to "2026-10-19T09:02:24Z",
            "2026-10-19T18:02+09:00" to "2026-10-19T09:02:00Z",
            "2026-10-19T05:02:24-04:00" to "2026-10-19T09:02:24Z",
            "2026-10-19T09:02:24.125Z" to "2026-10-19T09:02:24.125Z",
            " 2026-10-19T09:02Z " to "2026-10-19T09:02:00Z",
        )) {
            assertEquals(input, expected, PatchUploadTimeProtocol.normalizeTimestamp(input))
        }
    }

    @Test
    fun rejectsInvalidDatesMissingOffsetsAndNonDates() {
        for (value in listOf(
            null, "", " ", "null", "1760864544", "2026-10-19", "2026-10-19T09:02",
            "2026-10-19 09:02Z", "2026-02-29T09:02Z", "2026-02-30T09:02Z",
            "2026-13-19T09:02Z", "2026-10-19T24:02Z", "2026-10-19T09:02:60Z",
            "2026-10-19T09:02:24+25:00", "2026-10-19T09:02Z" + " ".repeat(128),
        )) {
            assertNull(value, PatchUploadTimeProtocol.normalizeTimestamp(value))
        }
    }

    @Test
    fun neverInfersTimeFromTheDeviceTimezone() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Seoul", "America/Los_Angeles", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("2026-10-19T09:02:00Z",
                    PatchUploadTimeProtocol.normalizeTimestamp("2026-10-19T09:02Z"))
                assertNull(PatchUploadTimeProtocol.normalizeTimestamp("2026-10-19T09:02"))
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun indexNormalizesUploadTimeWhilePreservingThePinnedRelease() {
        val parsed = PatchProtocol.releasesForRoute(index("2026-10-19T09:02Z"), release.route).single()
        assertEquals(release.copy(uploadedAt = "2026-10-19T09:02:00Z"), parsed)
    }

    @Test
    fun optionalMalformedIndexTimeDoesNotInvalidateTheReleaseOrInventADate() {
        for (value in listOf(null, JSONObject.NULL, "", "not a time", 1, true, JSONObject())) {
            assertEquals(release, PatchProtocol.releasesForRoute(index(value), release.route).single())
        }
    }

    @Test
    fun validIndexTimeAvoidsAllMetadataNetworkRequests() {
        val pinned = release.copy(uploadedAt = "2026-10-19T09:02Z")
        val before = pinned.copy()
        assertEquals("2026-10-19T09:02:00Z", PatchUploadTimeProtocol.lookup(pinned) {
            throw AssertionError("Valid index metadata must not use the API")
        })
        assertEquals(before, pinned)
    }

    @Test
    fun aValidIndexTimeReturnsBeforeFallbackUrlValidation() {
        val pinned = release.copy(uploadedAt = "2026-10-19T09:02Z", manifestUrl = "not a fallback URL")
        assertEquals("2026-10-19T09:02:00Z", PatchUploadTimeProtocol.lookup(pinned) {
            throw AssertionError("Index time must return before fallback validation/fetch")
        })
    }

    @Test
    fun missingOrInvalidIndexTimeFetchesOnlyThePinnedTag() {
        for (value in listOf(null, "wrong", "2026-02-30T09:02Z")) {
            val pinned = release.copy(uploadedAt = value)
            val requests = mutableListOf<String>()
            assertEquals("2026-10-19T09:02:24Z", PatchUploadTimeProtocol.lookup(pinned) {
                requests += it
                metadata().toString()
            })
            assertEquals(listOf("${API}tags/v3.3.0_r026_p0"), requests)
            assertEquals(release.manifestSha256, pinned.manifestSha256)
            assertEquals(value, pinned.uploadedAt)
        }
    }

    @Test
    fun anOlderReleaseNeverQueriesLatestOrAnotherTag() {
        val older = release.copy(
            patchVersion = "v3.2.0_r116_p3", gameVersion = "3.2.0", revision = "116",
            manifestUrl = "$DOWNLOAD/v3.2.0_r116_p3/INT_ANDROID_manifest.json",
        )
        val requests = mutableListOf<String>()
        assertEquals("2026-10-19T09:02:24Z", PatchUploadTimeProtocol.lookup(older) {
            requests += it
            metadata(older).toString()
        })
        assertEquals(listOf("${API}tags/v3.2.0_r116_p3"), requests)
    }

    @Test
    fun acceptsActualGitHubAssetSchemaAndUsesUploadRatherThanPublicationOrCommitTime() {
        // Values observed from GET releases/tags/v3.3.0_r026_p0, 2026-10-19.
        assertEquals("2026-10-19T09:02:24Z",
            PatchUploadTimeProtocol.parseGitHubRelease(metadata().toString(), release))
    }

    @Test
    fun absentOrInvalidUploadTimeNeverFallsBackToPublicationModificationOrCommitTime() {
        for (value in listOf(null, JSONObject.NULL, "invalid", "2026-02-30T09:02Z", 123)) {
            val root = metadata()
            root.getJSONArray("assets").getJSONObject(0).put("created_at", value)
            assertNull(PatchUploadTimeProtocol.parseGitHubRelease(root.toString(), release))
            assertNull(PatchUploadTimeProtocol.lookup(release) { root.toString() })
        }
    }

    @Test
    fun rejectsAnotherTagRepositoryDraftOrPrereleaseEvenWhenTheTimeIsValid() {
        for ((field, value) in listOf(
            "tag_name" to "v3.3.0_r021_p0",
            "html_url" to "https://github.com/foreign/repo/releases/tag/${release.patchVersion}",
            "url" to "https://api.github.com/repos/foreign/repo/releases/417090209",
            "url" to "${API}latest",
            "draft" to true,
            "prerelease" to true,
            "draft" to "false",
            "prerelease" to JSONObject.NULL,
        )) {
            val root = metadata().put(field, value)
            assertThrows(field, IllegalArgumentException::class.java) {
                PatchUploadTimeProtocol.parseGitHubRelease(root.toString(), release)
            }
        }
    }

    @Test
    fun rejectsMissingOrDuplicatedMatchingAssetsEvenWithValidPublicationTime() {
        for (assets in listOf(JSONArray(), JSONArray(listOf(asset(), asset())))) {
            assertThrows(IllegalArgumentException::class.java) {
                PatchUploadTimeProtocol.parseGitHubRelease(metadata().put("assets", assets).toString(), release)
            }
        }
    }

    @Test
    fun verifiesBothAssetNameAndExactUrlAndCompletedUploadState() {
        for ((field, value) in listOf(
            "name" to "CN_ANDROID_manifest.json",
            "browser_download_url" to release.manifestUrl.replace("v3.3.0_r026_p0", "v3.3.0_r021_p0"),
            "browser_download_url" to "https://example.test/INT_ANDROID_manifest.json",
            "browser_download_url" to "${release.manifestUrl}?different=1",
            "state" to "starter",
            "state" to JSONObject.NULL,
        )) {
            assertThrows(field, IllegalArgumentException::class.java) {
                PatchUploadTimeProtocol.parseGitHubRelease(
                    metadata().put("assets", JSONArray().put(asset().put(field, value))).toString(), release)
            }
        }
    }

    @Test
    fun selectsTheManifestForTheCorrectRouteAmongOtherReleaseAssets() {
        val otherRoute = release.copy(route = "CN_ANDROID",
            manifestUrl = release.manifestUrl.replace("INT_ANDROID", "CN_ANDROID"))
        val otherAsset = asset(otherRoute).put("created_at", "2026-10-19T10:00:00Z")
        val root = metadata().put("assets", JSONArray(listOf(otherAsset, asset())))
        assertEquals("2026-10-19T09:02:24Z", PatchUploadTimeProtocol.parseGitHubRelease(root.toString(), release))
    }

    @Test
    fun rejectsConflictingAssetDigestWithoutReplacingPinnedManifestIntegrity() {
        for (digest in listOf("sha256:" + "a".repeat(64), "sha512:" + "a".repeat(64), "", 123)) {
            val root = metadata()
            root.getJSONArray("assets").getJSONObject(0).put("digest", digest)
            assertThrows(Exception::class.java) { PatchUploadTimeProtocol.lookup(release) { root.toString() } }
        }
        assertEquals("2003bb96aa02d11daab82c228140e9cc54ec20c8107277c04ac593a93d3695e9", release.manifestSha256)
    }

    @Test
    fun acceptsOlderAssetsWithoutDigestAndCaseInsensitiveMatchingDigests() {
        for (digest in listOf(null, JSONObject.NULL, "SHA256:${release.manifestSha256.uppercase()}")) {
            val root = metadata()
            root.getJSONArray("assets").getJSONObject(0).put("digest", digest)
            assertEquals("2026-10-19T09:02:24Z", PatchUploadTimeProtocol.parseGitHubRelease(root.toString(), release))
        }
    }

    @Test
    fun rejectsUntrustedOrMismatchedManifestUrlsBeforeMakingAnyRequest() {
        for (url in listOf(
            release.manifestUrl.replace("https:", "http:"),
            release.manifestUrl.replace("github.com/", "github.com.evil.test/"),
            release.manifestUrl.replace("github.com/", "credentials@github.com/"),
            release.manifestUrl.replace("github.com/", "github.com:444/"),
            release.manifestUrl.replace("maynut02/", "foreign/"),
            release.manifestUrl.replace("v3.3.0_r026_p0", "v3.3.0_r021_p0"),
            release.manifestUrl.replace("INT_ANDROID", "CN_ANDROID"),
            release.manifestUrl.replace("/INT_ANDROID", "/../INT_ANDROID"),
            release.manifestUrl.replace("v3.3.0", "v3%2E3.0"),
            release.manifestUrl + "?tag=another",
            release.manifestUrl + "#other",
        )) {
            assertThrows(url, IllegalArgumentException::class.java) {
                PatchUploadTimeProtocol.lookup(release.copy(manifestUrl = url)) {
                    throw AssertionError("Invalid URL must not trigger metadata traffic")
                }
            }
        }
    }

    @Test
    fun rejectsMalformedReleaseIdentityAndLatestAliasBeforeNetworking() {
        for (invalid in listOf(
            release.copy(manifestSha256 = "invalid"),
            release.copy(patchVersion = "../latest"),
            release.copy(route = "../INT_ANDROID"),
            release.copy(patchVersion = "latest", manifestUrl = "$DOWNLOAD/latest/INT_ANDROID_manifest.json"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PatchUploadTimeProtocol.lookup(invalid) {
                    throw AssertionError("Invalid identity must not trigger metadata traffic")
                }
            }
        }
    }

    @Test
    fun invalidMetadataPropagatesWithoutTryingAnotherRelease() {
        for (response in listOf("{}", "{\"message\":\"API rate limit exceeded\"}")) {
            var requests = 0
            assertThrows(IllegalArgumentException::class.java) {
                PatchUploadTimeProtocol.lookup(release) { requests++; response }
            }
            assertEquals(1, requests)
        }
        for (response in listOf("not json", "[]")) {
            assertThrows(JSONException::class.java) { PatchUploadTimeProtocol.lookup(release) { response } }
        }
    }

    @Test
    fun networkRateLimitAndNotFoundErrorsPropagateWithoutTryingAnotherRelease() {
        var requests = 0
        val failure = IOException("HTTP 403/404")
        assertEquals(failure, assertThrows(IOException::class.java) {
            PatchUploadTimeProtocol.lookup(release) { requests++; throw failure }
        })
        assertEquals(1, requests)
        assertTrue(release.uploadedAt == null)
    }

    private fun index(timestamp: Any?): String {
        val item = JSONObject().put("route", release.route).put("channel", "release")
            .put("gameVersion", release.gameVersion).put("revision", release.revision)
            .put("catalogHash", release.catalogHash).put("patchVersion", release.patchVersion)
            .put("manifestUrl", release.manifestUrl).put("manifestSha256", release.manifestSha256)
            .put("uploadedAt", timestamp)
        return JSONObject().put("schemaVersion", 1).put("releases", JSONArray().put(item)).toString()
    }

    private fun metadata(selected: ReleaseEntry = release): JSONObject = JSONObject()
        .put("url", "${API}417090209")
        .put("html_url", "https://github.com/maynut02/astral-party-korean-patch/releases/tag/${selected.patchVersion}")
        .put("tag_name", selected.patchVersion).put("draft", false).put("prerelease", false)
        .put("created_at", "2026-10-15T11:31:56Z")
        .put("published_at", "2026-10-19T09:02:31Z")
        .put("assets", JSONArray().put(asset(selected)))

    private fun asset(selected: ReleaseEntry = release): JSONObject = JSONObject()
        .put("name", "${selected.route}_manifest.json").put("browser_download_url", selected.manifestUrl)
        .put("state", "uploaded").put("created_at", "2026-10-19T09:02:24Z")
        .put("updated_at", "2026-10-19T09:02:25Z").put("digest", "sha256:${selected.manifestSha256}")

    companion object {
        private const val DOWNLOAD = "https://github.com/maynut02/astral-party-korean-patch/releases/download"
        private const val API = "https://api.github.com/repos/maynut02/astral-party-korean-patch/releases/"
    }
}
