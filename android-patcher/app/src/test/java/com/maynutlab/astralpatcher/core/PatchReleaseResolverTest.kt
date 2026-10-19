package com.maynutlab.astralpatcher.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PatchReleaseResolverTest {
    private val catalog = CatalogIdentity("3.3.0", "b".repeat(32), null)

    @Test
    fun ordersPatchNumbersNumericallyWithoutReplacingMetadataRevisionWithTagRevision() {
        val p9 = entry("v3.3.0_r014_p9", revision = "012")
        val p10 = entry("v3.3.0_r014_p10", revision = "012", digest = "c".repeat(64))
        for (entries in listOf(arrayOf(p9, p10), arrayOf(p10, p9))) {
            val json = index(*entries)
            assertEquals(listOf("v3.3.0_r014_p10", "v3.3.0_r014_p9"),
                PatchProtocol.releasesForRoute(json, "INT_ANDROID").map { it.patchVersion })
            val release = requireNotNull(PatchProtocol.resolveRelease(json, catalog, "INT_ANDROID"))
            assertEquals("v3.3.0_r014_p10", release.patchVersion)
            assertEquals("012", release.revision)
            assertEquals("c".repeat(64), release.manifestSha256)
            assertEquals(release, PatchProtocol.latestReleaseForRoute(json, "INT_ANDROID"))
        }
    }

    @Test
    fun ordersGameAndMetadataRevisionBeforePatchNumber() {
        val json = index(
            entry("v3.9.0_r999_p999", game = "3.9.0", revision = "999"),
            entry("v3.10.0_r014_p999", game = "3.10.0", revision = "009"),
            entry("v3.10.0_r001_p0", game = "3.10.0", revision = "010"),
        )
        assertEquals("v3.10.0_r001_p0", PatchProtocol.latestReleaseForRoute(json, "INT_ANDROID")?.patchVersion)
    }

    @Test
    fun ordersPatchNumbersBeyondLongRange() {
        val huge = "999999999999999999999999999999999"
        val json = index(entry("v3.3.0_r014_p9"), entry("v3.3.0_r014_p$huge"))
        assertEquals("v3.3.0_r014_p$huge", PatchProtocol.resolveRelease(json, catalog, "INT_ANDROID")?.patchVersion)
    }

    @Test
    fun legacyTagWithoutPatchSuffixPrecedesP1() {
        val json = index(entry("v3.3.0_r014"), entry("v3.3.0_r014_p1"))
        assertEquals("v3.3.0_r014_p1", PatchProtocol.resolveRelease(json, catalog, "INT_ANDROID")?.patchVersion)
    }

    @Test
    fun keepsRouteChannelAndApkVersionCandidateFiltersSeparateFromCatalogCompatibility() {
        val json = index(
            entry("v3.3.0_r014_p9"),
            entry("v3.2.0_r116_p2", game = "3.2.0", revision = "116"),
            entry("v3.3.0_r014_p10", route = "CN_ANDROID"),
            entry("v3.3.0_r014_p11", channel = "preview"),
        )
        assertEquals(listOf("v3.3.0_r014_p9"),
            PatchProtocol.releasesForRoute(json, "INT_ANDROID", "3.3.0").map { it.patchVersion })
        assertEquals(listOf("v3.3.0_r014_p10"),
            PatchProtocol.releasesForRoute(json, "CN_ANDROID").map { it.patchVersion })
        assertTrue(PatchProtocol.releasesForRoute(json, "INT_ANDROID", "4.0.0").isEmpty())
        assertNull(PatchProtocol.latestReleaseForRoute(json, "INT_ANDROID", "4.0.0"))
        val fallback = PatchProtocol.releasesForRoute(json, "INT_ANDROID", "4.0.0")
            .ifEmpty { PatchProtocol.releasesForRoute(json, "INT_ANDROID") }
        assertEquals(2, fallback.size)
        assertTrue(PatchProtocol.compatibleReleases(json, catalog.copy(catalogHash = "c".repeat(32)), "INT_ANDROID").isEmpty())
        assertEquals(1, PatchProtocol.compatibleReleases(json, catalog, "INT_ANDROID").size)
    }

    @Test
    fun unknownCatalogProducesNoClaimOfCompatibility() {
        val json = index(entry("v3.3.0_r014_p3"))
        for (identity in listOf(catalog.copy(catalogHash = ""), catalog.copy(gameVersion = ""))) {
            assertTrue(PatchProtocol.compatibleReleases(json, identity, "INT_ANDROID").isEmpty())
            assertNull(PatchProtocol.resolveRelease(json, identity, "INT_ANDROID"))
        }
        assertEquals(1, PatchProtocol.releasesForRoute(json, "INT_ANDROID", "3.3.0").size)
    }

    @Test
    fun normalizesCatalogDigestCaseWithoutChangingThePatchIdentity() {
        val json = index(entry("v3.3.0_r014_p3").put("catalogHash", "B".repeat(32)))
        val release = PatchProtocol.resolveRelease(json, catalog.copy(catalogHash = "B".repeat(32)), "INT_ANDROID")
        assertEquals("b".repeat(32), release?.catalogHash)
        assertEquals("v3.3.0_r014_p3", release?.patchVersion)
    }

    @Test
    fun compatibleListExposesBothRevisionsButExactResolverRejectsAmbiguity() {
        val json = index(entry("v3.3.0_r014_p10", revision = "012"), entry("v3.3.0_r026_p0", revision = "026"))
        assertEquals(2, PatchProtocol.compatibleReleases(json, catalog, "INT_ANDROID").size)
        assertThrows(IllegalStateException::class.java) { PatchProtocol.resolveRelease(json, catalog, "INT_ANDROID") }
        assertEquals("v3.3.0_r026_p0", PatchProtocol.latestReleaseForRoute(json, "INT_ANDROID")?.patchVersion)
    }

    @Test
    fun rejectsExactDuplicateEvenWhenDigestsAreIdentical() {
        val item = entry("v3.3.0_r014_p3")
        val json = index(item, JSONObject(item.toString()))
        assertThrows(IllegalStateException::class.java) { PatchProtocol.releasesForRoute(json, "INT_ANDROID") }
        assertThrows(IllegalStateException::class.java) { PatchProtocol.resolveRelease(json, catalog, "INT_ANDROID") }
        assertThrows(IllegalStateException::class.java) { PatchProtocol.latestReleaseForRoute(json, "INT_ANDROID") }
    }

    @Test
    fun rejectsSameReleaseIdentityWithConflictingDigestOrCatalog() {
        val original = entry("v3.3.0_r014_p3")
        for (conflict in listOf(
            entry("v3.3.0_r014_p3", digest = "c".repeat(64)),
            entry("v3.3.0_r014_p3").put("catalogHash", "c".repeat(32)),
        )) {
            assertThrows(IllegalStateException::class.java) {
                PatchProtocol.resolveRelease(index(original, conflict), catalog, "INT_ANDROID")
            }
        }
    }

    @Test
    fun tiedNumericPatchNumbersRequireExplicitSelectionInsteadOfUploadTimeTieBreak() {
        val json = index(
            entry("v3.3.0_r014_p1").put("uploadedAt", "2026-10-19T12:00:00Z"),
            entry("v3.3.0_r014_p01").put("uploadedAt", "2026-10-18T12:00:00Z"),
        )
        assertEquals(2, PatchProtocol.releasesForRoute(json, "INT_ANDROID").size)
        assertThrows(IllegalStateException::class.java) { PatchProtocol.latestReleaseForRoute(json, "INT_ANDROID") }
        assertThrows(IllegalStateException::class.java) { PatchProtocol.resolveRelease(json, catalog, "INT_ANDROID") }
    }

    @Test
    fun selectedEntryRetainsManifestDigestAfterIndexRefresh() {
        val selected = requireNotNull(PatchProtocol.resolveRelease(index(entry("v3.3.0_r014_p9")), catalog, "INT_ANDROID"))
        val refreshed = requireNotNull(PatchProtocol.resolveRelease(
            index(entry("v3.3.0_r014_p10", digest = "c".repeat(64))), catalog, "INT_ANDROID"))
        assertEquals("a".repeat(64), selected.manifestSha256)
        assertEquals("v3.3.0_r014_p9", selected.patchVersion)
        assertEquals("c".repeat(64), refreshed.manifestSha256)
    }

    @Test
    fun rejectsUntrustedOrInvalidCandidateBeforeOfferingIt() {
        for (item in listOf(
            entry("v3.3.0_r014_p3").put("manifestUrl", "https://example.test/manifest.json"),
            entry("v3.3.0_r014_p3").put("manifestSha256", "invalid"),
            entry("v3.3.0_r014_p3").put("catalogHash", "invalid"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PatchProtocol.releasesForRoute(index(item), "INT_ANDROID")
            }
        }
    }

    private fun entry(
        patch: String,
        game: String = "3.3.0",
        revision: String = "012",
        route: String = "INT_ANDROID",
        channel: String = "release",
        digest: String = "a".repeat(64),
    ) = JSONObject()
        .put("route", route).put("channel", channel).put("gameVersion", game)
        .put("revision", revision).put("catalogHash", "b".repeat(32)).put("patchVersion", patch)
        .put("manifestUrl", "https://github.com/maynut02/astral-party-korean-patch/releases/download/$patch/${route}_manifest.json")
        .put("manifestSha256", digest)

    private fun index(vararg entries: JSONObject) = JSONObject()
        .put("schemaVersion", 1).put("releases", JSONArray(entries.toList())).toString()
}
