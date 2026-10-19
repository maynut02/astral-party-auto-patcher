package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.CatalogIdentity
import com.maynutlab.astralpatcher.core.PatchFile
import com.maynutlab.astralpatcher.core.PatchManifest
import com.maynutlab.astralpatcher.core.PatchProtocol
import com.maynutlab.astralpatcher.core.PatchTargetDiagnostic
import com.maynutlab.astralpatcher.core.PatchTargetInspection
import com.maynutlab.astralpatcher.core.ReleaseEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PatchStatusEvidenceTest {
    private val latest = release(30)
    private val older = release(29)

    @Test fun contentsUseActualHashAndSizeRatherThanDiagnosticLabels() {
        val original = observation(latest, "original")
        val patched = observation(latest, "translation-30")
        assertEquals(PatchContents.ORIGINAL, patchContents(original))
        assertEquals(PatchContents.PATCHED, patchContents(patched))
        assertEquals(PatchContents.UNKNOWN, patchContents(observation(latest, "unverified bytes")))
        val forgedLabel = patched.copy(inspection = patched.inspection.copy(files = patched.inspection.files.map {
            it.copy(state = "ORIGINAL")
        }))
        assertEquals(PatchContents.PATCHED, patchContents(forgedLabel))
        val wrongSize = patched.copy(inspection = patched.inspection.copy(files = patched.inspection.files.map {
            it.copy(size = requireNotNull(it.size) + 1)
        }))
        assertEquals(PatchContents.UNKNOWN, patchContents(wrongSize))
    }

    @Test fun sourcePayloadSharedByEveryFileCountsAsOriginalFirst() {
        val observed = observation(latest, "original")
        val shared = observed.copy(manifest = observed.manifest.copy(files = observed.manifest.files.map {
            it.copy(payloadSha256 = it.sourceSha256, payloadSize = it.sourceSize)
        }))
        assertEquals(PatchContents.ORIGINAL, patchContents(shared))
    }

    @Test fun sharedUnchangedFileDoesNotHideAnAppliedTranslation() {
        val observed = observation(latest, "translation-30")
        val sharedFile = observed.manifest.files.single().copy(relativePath = "shared/hash/__data")
        val sharedDiagnostic = observed.inspection.files.single().copy(
            path = sharedFile.relativePath,
            actualPath = "cache/${sharedFile.relativePath}",
            candidates = listOf("cache/${sharedFile.relativePath}"),
            state = "ORIGINAL",
        )
        val shared = observed.copy(
            manifest = observed.manifest.copy(files = observed.manifest.files + sharedFile.copy(
                sourceSha256 = sharedFile.payloadSha256, sourceSize = sharedFile.payloadSize,
            )),
            inspection = observed.inspection.copy(total = 2, ready = 2, files = observed.inspection.files + sharedDiagnostic),
        )
        assertEquals(PatchContents.PATCHED, patchContents(shared))
    }

    @Test fun verifiedLatestWinsOverCompatibleOldOriginalObservation() {
        val primary = observation(latest, "translation-30")
        val compatible = observation(older, "original")
        val selected = selectPatchStatusObservation(primary, compatible, identity(), listOf(latest, older), {
            fail("verified latest needs no additional manifest")
            primary
        })
        assertSame(primary, selected)
    }

    @Test fun verifiedBaselineOriginalOrPatchNeedsNoAdditionalManifest() {
        val primary = observation(latest, "unknown")
        for (contents in listOf("original", "translation-29")) {
            val baseline = observation(older, contents)
            val selected = selectPatchStatusObservation(primary, baseline, identity(), listOf(latest, older), {
                fail("verified baseline needs no additional manifest")
                primary
            })
            assertSame(baseline, selected)
        }
    }

    @Test fun installedHistoryPrioritizesOnlyEligibleUninspectedManifest() {
        val primary = observation(latest, "unknown")
        val first = release(28)
        val historic = release(27)
        val loaded = mutableListOf<ReleaseEntry>()
        val expected = observation(historic, "translation-27")
        val selected = selectPatchStatusObservation(
            primary, null, identity(historic.patchVersion), listOf(latest, older, first, historic),
            { candidate -> loaded += candidate; expected },
        )
        assertEquals(listOf(historic), loaded)
        assertSame(expected, selected)
    }

    @Test fun historyTagDoesNotProvePatchWhenManifestBytesDoNotMatch() {
        val primary = observation(latest, "unknown")
        val loaded = mutableListOf<ReleaseEntry>()
        val selected = selectPatchStatusObservation(primary, null, identity(older.patchVersion), listOf(latest, older), {
            loaded += it
            observation(it, "unknown")
        })
        assertEquals(listOf(older), loaded)
        assertSame(primary, selected)
    }

    @Test fun differentRouteGameAndCatalogAreNeverHistoryCandidates() {
        val primary = observation(latest, "unknown")
        val foreign = listOf(
            older.copy(route = "CN_ANDROID"),
            older.copy(gameVersion = "3.2.0"),
            older.copy(catalogHash = "d".repeat(32)),
        )
        for (history in foreign) {
            val selected = selectPatchStatusObservation(primary, null, identity(history.patchVersion), listOf(latest) + foreign, {
                fail("incompatible catalog history must not be loaded")
                primary
            })
            assertSame(primary, selected)
        }
    }

    @Test fun withoutCatalogIdentityNoOlderManifestIsGuessed() {
        val primary = observation(latest, "unknown")
        assertSame(primary, selectPatchStatusObservation(primary, null, null, listOf(latest, older), {
            fail("no catalog identity")
            primary
        }))
    }

    @Test fun searchPreservesIndexOrderAndStopsAtFirstVerifiedOldPayload() {
        val primary = observation(latest, "unknown")
        val next = release(28)
        val loaded = mutableListOf<ReleaseEntry>()
        val expected = observation(next, "translation-28")
        val selected = selectPatchStatusObservation(primary, null, identity(), listOf(latest, older, next, release(27)), {
            loaded += it
            if (it == next) expected else observation(it, "unknown")
        })
        assertEquals(listOf(older, next), loaded)
        assertSame(expected, selected)
    }

    @Test fun olderOriginalOrCatalogMismatchCannotClaimAnInstalledOldPatch() {
        val primary = observation(latest, "unknown")
        val next = release(28)
        val expected = observation(next, "translation-28")
        for (first in listOf(
            observation(older, "original"),
            observation(older, "translation-29").let { it.copy(inspection = it.inspection.copy(catalogMatches = false)) },
        )) {
            assertSame(expected, selectPatchStatusObservation(primary, null, identity(), listOf(latest, older, next), {
                if (it == older) first else expected
            }))
        }
    }

    @Test fun baselineCatalogMismatchDoesNotStopSearchDespiteMatchingPayload() {
        val primary = observation(latest, "translation-30").let {
            it.copy(inspection = it.inspection.copy(catalogMatches = false))
        }
        val expected = observation(older, "translation-29")
        assertSame(expected, selectPatchStatusObservation(primary, null, identity(), listOf(latest, older), { expected }))
    }

    @Test fun searchLoadsAtMostEightPreviouslyUninspectedManifests() {
        val primary = observation(latest, "unknown")
        val compatible = observation(older, "unknown")
        val remaining = (28 downTo 16).map(::release)
        val loaded = mutableListOf<ReleaseEntry>()
        val selected = selectPatchStatusObservation(primary, compatible, identity(), listOf(latest, older) + remaining, {
            loaded += it
            observation(it, "unknown")
        })
        assertEquals(remaining.take(8), loaded)
        assertSame(compatible, selected)
    }

    @Test fun firstManifestFailureReportsOnceThenReturnsBaselineWithoutFurtherNetworkWork() {
        val primary = observation(latest, "unknown")
        val compatible = observation(release(28), "unknown")
        val failure = IllegalStateException("manifest unavailable")
        val loaded = mutableListOf<ReleaseEntry>()
        val reported = mutableListOf<Pair<ReleaseEntry, Exception>>()
        val selected = selectPatchStatusObservation(
            primary, compatible, identity(), listOf(latest, older, release(28), release(27)),
            { loaded += it; throw failure },
            { candidate, exception -> reported += candidate to exception },
        )
        assertSame(compatible, selected)
        assertEquals(listOf(older), loaded)
        assertEquals(listOf(older to failure), reported)
    }

    private fun release(revision: Int) = ReleaseEntry(
        route = "INT_ANDROID", gameVersion = "3.3.0", revision = revision.toString().padStart(3, '0'),
        catalogHash = "a".repeat(32), patchVersion = "v3.3.0_r${revision.toString().padStart(3, '0')}_p0",
        manifestUrl = "https://example.test/$revision/manifest.json", manifestSha256 = "b".repeat(64),
    )

    private fun identity(history: String? = null) = CatalogIdentity("3.3.0", "a".repeat(32), history)

    private fun observation(release: ReleaseEntry, actual: String): PatchStatusObservation {
        val original = "original".toByteArray()
        val payload = "translation-${release.revision.toInt()}".toByteArray()
        val bytes = actual.toByteArray()
        val file = PatchFile(
            relativePath = "bundle/hash/__data",
            downloadUrl = "https://example.test/payload", downloadSha256 = PatchProtocol.sha256(payload), downloadSize = payload.size.toLong(),
            payloadSha256 = PatchProtocol.sha256(payload), payloadSize = payload.size.toLong(),
            sourceDownloadUrl = "https://example.test/source", sourceDownloadSha256 = PatchProtocol.sha256(original), sourceDownloadSize = original.size.toLong(),
            sourceSha256 = PatchProtocol.sha256(original), sourceSize = original.size.toLong(),
        )
        val state = when (actual) {
            "original" -> "ORIGINAL"
            String(payload) -> "CURRENT_PATCH"
            else -> "UNKNOWN_MODIFIED"
        }
        return PatchStatusObservation(
            PatchManifest(release.route, release.patchVersion, release.gameVersion, release.revision, release.catalogHash, listOf(file)),
            PatchTargetInspection(
                total = 1, ready = if (state == "UNKNOWN_MODIFIED") 0 else 1, missing = 0,
                incompatible = if (state == "UNKNOWN_MODIFIED") 1 else 0,
                files = listOf(PatchTargetDiagnostic(
                    file.relativePath, "cache/${file.relativePath}", bytes.size.toLong(), PatchProtocol.sha256(bytes), state,
                    candidates = listOf("cache/${file.relativePath}"),
                )),
                catalogHash = release.catalogHash, catalogMatches = true, planJson = "{}",
            ),
        )
    }
}
