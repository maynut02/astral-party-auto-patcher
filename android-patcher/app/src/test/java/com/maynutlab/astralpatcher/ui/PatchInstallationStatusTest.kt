package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.CatalogIdentity
import com.maynutlab.astralpatcher.core.PatchFile
import com.maynutlab.astralpatcher.core.PatchManifest
import com.maynutlab.astralpatcher.core.PatchProtocol
import com.maynutlab.astralpatcher.core.PatchTargetDiagnostic
import com.maynutlab.astralpatcher.core.PatchTargetInspection
import com.maynutlab.astralpatcher.core.ReleaseEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchInstallationStatusTest {
    private val originals = listOf("original-table", "original-font", "shared-assets")
    private val payloads = listOf("patched-table", "patched-font", "shared-assets")
    private val manifest = PatchManifest(
        route = "INT_ANDROID",
        patchVersion = "v3.3.0_r026_p0",
        gameVersion = "3.3.0",
        revision = "026",
        catalogHash = "a".repeat(32),
        files = originals.indices.map { index ->
            val source = originals[index].toByteArray()
            val payload = payloads[index].toByteArray()
            PatchFile(
                relativePath = "bundle$index/${"b".repeat(32)}/__data",
                downloadUrl = "https://example.test/payload$index",
                downloadSha256 = PatchProtocol.sha256(payload),
                downloadSize = payload.size.toLong(),
                payloadSha256 = PatchProtocol.sha256(payload),
                payloadSize = payload.size.toLong(),
                sourceDownloadUrl = "https://example.test/source$index",
                sourceDownloadSha256 = PatchProtocol.sha256(source),
                sourceDownloadSize = source.size.toLong(),
                sourceSha256 = PatchProtocol.sha256(source),
                sourceSize = source.size.toLong(),
            )
        },
    )

    @Test fun currentPayloadIsGreenIncludingSourceFirstSharedFileAndDynamicCacheSetting() {
        val observed = inspection().copy(warnings = listOf(
            "APK_CATALOG_PATH: package catalog",
            "UNRESOLVED_CACHE_OVERRIDE: {App.WebServerConfig.CacheDirectoryPath}",
        ))
        assertEquals("ORIGINAL", observed.files.last().state)
        val status = resolve(inspection = observed)
        assertEquals("최신 한글패치 설치 완료", status.label)
        assertEquals(manifest.patchVersion, status.description)
        assertEquals(StatusIndicator.COMPLETED, status.indicator)
        assertEquals(manifest.patchVersion, status.installedPatchVersion)
    }

    @Test fun verifiedCurrentPayloadDoesNotRequireHistoryOrTrustAnOlderHistoryTag() {
        for (history in listOf(null, identity(null, "NONE"), identity("old-record"), identity("old-record", "CORRUPT"))) {
            assertEquals(StatusIndicator.COMPLETED, resolve(identity = history).indicator)
            assertEquals(manifest.patchVersion, resolve(identity = history).description)
        }
    }

    @Test fun historyOrReadySummaryAloneNeverProvesInstalledOrUninstalled() {
        for (history in listOf(null, identity(null, "NONE"), identity(), identity(status = "RESTORED"), identity(status = "CORRUPT"))) {
            for (observed in listOf(null, inspection().copy(files = emptyList()))) {
                assertEquals(StatusIndicator.ERROR, resolve(identity = history, inspection = observed).indicator)
            }
        }
    }

    @Test fun verifiedOriginalsWithoutInstallRecordAreNeutral() {
        val status = resolve(identity = identity(null, "NONE"), inspection = inspection(originals))
        assertEquals("한글패치 미설치", status.label)
        assertEquals("설치 버튼을 누르면 최신 한글패치 리소스를 적용합니다", status.description)
        assertEquals(StatusIndicator.NEUTRAL, status.indicator)
    }

    @Test fun originalsAreNeutralRegardlessOfRestoreOrInstallHistory() {
        for (history in listOf(null, identity(), identity(status = "RESTORED"), identity(status = "CORRUPT"), identity("older-tag"))) {
            val status = resolve(identity = history, inspection = inspection(originals))
            assertEquals("한글패치 미설치", status.label)
            assertEquals(StatusIndicator.NEUTRAL, status.indicator)
            assertNull(status.installedPatchVersion)
        }
        assertEquals("최신 한글패치 설치 완료", resolve(identity = identity(status = "RESTORED")).label)
    }

    @Test fun allSharedSourceAndPayloadFilesAreUninstalledEvenWithCurrentHistory() {
        val sharedManifest = manifest.copy(files = manifest.files.map { file ->
            file.copy(payloadSha256 = file.sourceSha256, payloadSize = file.sourceSize)
        })
        val status = resolvePatchInstallationStatus(
            identity(), inspection(originals), sharedManifest, manifest.gameVersion, release(), listOf(release()),
        )
        assertEquals("한글패치 미설치", status.label)
        assertEquals(StatusIndicator.NEUTRAL, status.indicator)
        assertNull(status.installedPatchVersion)
    }

    @Test fun missingFilesSuggestGameResourceDownload() {
        val status = resolve(inspection = inspection(listOf(payloads[0], null, payloads[2])))
        assertEquals(StatusIndicator.ERROR, status.indicator)
        assertTrue(status.description.contains("1개"))
        assertTrue(status.description.contains("다운로드"))
    }

    @Test fun inaccessibleFilesAreErrorsRatherThanMissingOrNotInstalled() {
        val observed = inspection()
        val unreadable = observed.copy(
            ready = observed.ready - 1,
            unreadable = 1,
            planJson = null,
            files = observed.files.mapIndexed { index, file ->
                if (index == 0) file.copy(state = "UNREADABLE", error = "EACCES") else file
            },
        )
        val status = resolve(inspection = unreadable)
        assertEquals("한글패치 확인 실패", status.label)
        assertEquals(StatusIndicator.ERROR, status.indicator)
        assertTrue(status.description.contains("권한"))
    }

    @Test fun ambiguousPathsRequireCheckingRatherThanClaimingSuccessfulInstallation() {
        val observed = inspection()
        val status = resolve(inspection = observed.copy(files = observed.files.mapIndexed { index, file ->
            if (index == 0) file.copy(candidates = listOf(file.actualPath, "other-cache/${file.path}")) else file
        }))
        assertEquals(StatusIndicator.ERROR, status.indicator)
    }

    @Test fun mixedAndUnknownContentsNeedCheckingWithoutPresumingCorruption() {
        for (contents in listOf(
            listOf(originals[0], payloads[1], payloads[2]),
            listOf("an older translation", payloads[1], payloads[2]),
        )) {
            val status = resolve(inspection = inspection(contents))
            assertEquals(StatusIndicator.ERROR, status.indicator)
            assertFalse(status.description.contains("손상"))
        }
    }

    @Test fun stateLabelOrCorrectHashWithWrongSizeCannotProveInstallation() {
        val observed = inspection()
        val first = observed.files.first()
        for (changed in listOf(
            first.copy(sha256 = "0".repeat(64)),
            first.copy(size = requireNotNull(first.size) + 1),
        )) {
            assertEquals("CURRENT_PATCH", changed.state)
            val status = resolve(inspection = observed.copy(files = listOf(changed) + observed.files.drop(1)))
            assertEquals(StatusIndicator.ERROR, status.indicator)
        }
    }

    @Test fun updateUsesNewestFirstMetadataOrderEvenWhenTagLooksOlder() {
        val newer = release().copy(patchVersion = "v3.3.0_r014_p3", revision = "027")
        val status = resolve(latest = newer, candidates = listOf(newer, release()))
        assertEquals("한글패치 업데이트 필요", status.label)
        assertEquals(StatusIndicator.PENDING, status.indicator)
        assertEquals(manifest.patchVersion, status.installedPatchVersion)
        assertTrue(status.description.contains(manifest.patchVersion))
        assertTrue(status.description.contains(newer.patchVersion))
    }

    @Test fun latestInformationMustAgreeWithVerifiedManifestAndNewestFirstOrder() {
        val current = release()
        val different = current.copy(patchVersion = "v3.3.0_r999_p0", revision = "999")
        val cases = listOf(
            different to listOf(current, different),
            current to listOf(different, current),
            different to listOf(different),
            different to listOf(current),
            different.copy(route = "CN_ANDROID") to listOf(different.copy(route = "CN_ANDROID"), current),
            current.copy(revision = "999") to listOf(current),
            current.copy(gameVersion = "3.4.0") to listOf(current),
            current.copy(catalogHash = "d".repeat(32)) to listOf(current),
        )
        for ((latest, candidates) in cases) {
            val status = resolve(latest = latest, candidates = candidates)
            assertEquals(StatusIndicator.ERROR, status.indicator)
            assertEquals("한글패치 버전 확인 실패", status.label)
            assertNull(status.installedPatchVersion)
        }
        assertEquals(StatusIndicator.ERROR, resolve(latest = null).indicator)
    }

    @Test fun olderVerifiedPatchStillNeedsUpdateWhenLatestTargetsAnotherGameVersion() {
        val newer = release().copy(gameVersion = "3.4.0", revision = "001", patchVersion = "v3.4.0_r001_p0", catalogHash = "d".repeat(32))
        val status = resolve(latest = newer, candidates = listOf(newer, release()))
        assertEquals("한글패치 업데이트 필요", status.label)
        assertEquals(StatusIndicator.PENDING, status.indicator)
        assertEquals(manifest.patchVersion, status.installedPatchVersion)
        assertTrue(status.description.contains("게임"))
        assertTrue(status.description.contains("3.4.0"))
        assertTrue(status.description.contains("업데이트"))
    }

    @Test fun verifiedOlderOriginalsStayUninstalledWithGameUpdateGuidance() {
        val latest = nextGameRelease()
        val status = resolve(inspection = inspection(originals), latest = latest, candidates = listOf(latest, release()))
        assertEquals("한글패치 미설치", status.label)
        assertEquals(StatusIndicator.NEUTRAL, status.indicator)
        assertNull(status.installedPatchVersion)
        assertTrue(status.description.contains("게임"))
        assertTrue(status.description.contains(latest.gameVersion))
        assertTrue(status.description.contains("업데이트"))
    }

    @Test fun outdatedApkWithoutOldResourceObservationsWaitsWithoutClaimingInstallation() {
        val latest = nextGameRelease()
        for ((observed, expected) in listOf(
            null to null,
            null to manifest,
            inspection().copy(files = emptyList()) to manifest,
        )) {
            val status = resolvePatchInstallationStatus(
                identity(), observed, expected, manifest.gameVersion, latest, listOf(latest, release()),
            )
            assertAwaitingGameUpdate(status, latest)
        }
    }

    @Test fun outdatedApkMissingOrUnknownFilesWaitForGameUpdateInsteadOfReportingFailure() {
        val latest = nextGameRelease()
        for (observed in listOf(
            inspection(listOf(null, null, null)),
            inspection(listOf(payloads[0], null, payloads[2])),
            inspection(listOf("unrecognized old resource", payloads[1], payloads[2])),
            inspection(listOf(originals[0], payloads[1], payloads[2])),
        )) {
            assertAwaitingGameUpdate(resolve(inspection = observed, latest = latest, candidates = listOf(latest, release())), latest)
        }
    }

    @Test fun gameUpdateRequirementDoesNotHideReadAccessOrAmbiguousPathFailures() {
        val latest = nextGameRelease()
        val observed = inspection()
        for (failed in listOf(
            observed.copy(unreadable = 1),
            observed.copy(ambiguous = 1),
            observed.copy(files = observed.files.mapIndexed { index, file ->
                if (index == 0) file.copy(state = "UNREADABLE", error = "EACCES") else file
            }),
            observed.copy(files = observed.files.mapIndexed { index, file ->
                if (index == 0) file.copy(candidates = listOf(file.actualPath, "another-cache/${file.path}")) else file
            }),
        )) {
            val status = resolve(inspection = failed, latest = latest, candidates = listOf(latest, release()))
            assertEquals(StatusIndicator.ERROR, status.indicator)
            assertNull(status.installedPatchVersion)
        }
    }

    @Test fun gameUpdateRequirementDoesNotHideMetadataQueryFailures() {
        val latest = nextGameRelease()
        val status = resolvePatchInstallationStatus(
            identity(), null, null, manifest.gameVersion, latest, listOf(latest, release()), error = "manifest 조회 실패",
        )
        assertEquals("한글패치 확인 실패", status.label)
        assertEquals(StatusIndicator.ERROR, status.indicator)
        assertTrue(status.description.contains("조회 실패"))
    }

    private fun assertAwaitingGameUpdate(status: PatchInstallationStatus, latest: ReleaseEntry) {
        assertEquals("한글패치 확인 대기", status.label)
        assertEquals(StatusIndicator.NEUTRAL, status.indicator)
        assertNull(status.installedPatchVersion)
        assertTrue(status.description.contains("게임"))
        assertTrue(status.description.contains(latest.gameVersion))
        assertTrue(status.description.contains("업데이트"))
        assertFalse(status.description.contains("리소스를 모두 다운로드"))
    }

    private fun nextGameRelease() = release().copy(
        gameVersion = "3.4.0", revision = "001", patchVersion = "v3.4.0_r001_p0", catalogHash = "d".repeat(32),
    )

    @Test fun catalogAndApkMismatchAreErrorsEvenWithMatchingPayloadBytes() {
        for ((observed, gameVersion) in listOf(
            inspection().copy(catalogMatches = false) to manifest.gameVersion,
            inspection() to "3.2.0",
            inspection() to null,
        )) {
            val status = resolve(inspection = observed, installedGameVersion = gameVersion)
            assertEquals(StatusIndicator.ERROR, status.indicator)
            assertEquals("한글패치 확인 실패", status.label)
            assertNull(status.installedPatchVersion)
        }
    }

    @Test fun incompleteBundleRecordWarningDoesNotOverrideVerifiedFileContents() {
        val warnings = listOf("CATALOG_BUNDLE_RECORDS_UNCONFIRMED: incomplete catalog")
        assertEquals(StatusIndicator.COMPLETED, resolve(inspection = inspection().copy(warnings = warnings)).indicator)
        assertEquals(StatusIndicator.NEUTRAL, resolve(inspection = inspection(originals).copy(warnings = warnings)).indicator)
    }

    @Test fun duplicateManifestEvidenceAndDuplicateActualPathsAreErrors() {
        val observed = inspection()
        for (changed in listOf(
            observed.copy(files = listOf(observed.files.first()) + observed.files.dropLast(1)),
            observed.copy(files = observed.files.map { it.copy(actualPath = "same-cache/__data", candidates = listOf("same-cache/__data")) }),
        )) {
            assertEquals(StatusIndicator.ERROR, resolve(inspection = changed).indicator)
        }
    }

    @Test fun staleHistoryCannotTurnUnknownBytesIntoAnInstalledOldPatch() {
        val newer = release().copy(patchVersion = "v3.3.0_r027_p0", revision = "027")
        val status = resolve(
            identity = identity(manifest.patchVersion),
            inspection = inspection(listOf("unverified older translation", payloads[1], payloads[2])),
            latest = newer,
            candidates = listOf(newer, release()),
        )
        assertEquals(StatusIndicator.ERROR, status.indicator)
    }

    @Test fun missingPlanUnavailableAccessAndQueryFailuresStayDistinct() {
        for (observed in listOf(
            inspection().copy(planJson = null),
            inspection().copy(files = inspection().files.drop(1)),
        )) {
            assertEquals(StatusIndicator.ERROR, resolve(inspection = observed).indicator)
        }
        val unavailable = resolvePatchInstallationStatus(
            identity(), null, null, manifest.gameVersion, null, emptyList(),
            unavailableReason = "Shizuku 연결을 기다리고 있습니다.", error = "not connected",
        )
        assertEquals("한글패치 확인 대기", unavailable.label)
        assertEquals(StatusIndicator.NEUTRAL, unavailable.indicator)
        for ((observed, expected) in listOf(null to manifest, inspection() to null)) {
            val failed = resolvePatchInstallationStatus(
                identity(), observed, expected, manifest.gameVersion, null, emptyList(), error = "조회 실패",
            )
            assertEquals("한글패치 확인 실패", failed.label)
            assertEquals(StatusIndicator.ERROR, failed.indicator)
        }
    }

    private fun identity(version: String? = manifest.patchVersion, status: String = "OK") = CatalogIdentity(
        gameVersion = manifest.gameVersion,
        catalogHash = manifest.catalogHash,
        installedPatchVersion = version,
        historyStatus = status,
    )

    private fun release() = ReleaseEntry(
        route = manifest.route,
        gameVersion = manifest.gameVersion,
        revision = manifest.revision,
        catalogHash = manifest.catalogHash,
        patchVersion = manifest.patchVersion,
        manifestUrl = "https://example.test/${manifest.patchVersion}/manifest.json",
        manifestSha256 = "c".repeat(64),
    )

    private fun inspection(contents: List<String?> = payloads): PatchTargetInspection {
        val files = manifest.files.mapIndexed { index, expected ->
            val bytes = contents[index]?.toByteArray()
            val hash = bytes?.let(PatchProtocol::sha256).orEmpty()
            val state = when {
                bytes == null -> "MISSING"
                hash == expected.sourceSha256 -> "ORIGINAL"
                hash == expected.payloadSha256 -> "CURRENT_PATCH"
                else -> "UNKNOWN_MODIFIED"
            }
            val actual = if (bytes == null) "" else "downloaded-cache/${expected.relativePath}"
            PatchTargetDiagnostic(
                path = expected.relativePath,
                actualPath = actual,
                size = bytes?.size?.toLong(),
                sha256 = hash,
                state = state,
                candidates = if (actual.isEmpty()) emptyList() else listOf(actual),
            )
        }
        return PatchTargetInspection(
            total = files.size,
            ready = files.count { it.isKnown },
            missing = files.count { it.state == "MISSING" },
            incompatible = files.count { it.state == "UNKNOWN_MODIFIED" },
            files = files,
            catalogHash = manifest.catalogHash,
            catalogMatches = true,
            planJson = if (files.all { it.canWrite }) "{}" else null,
        )
    }

    private fun resolve(
        identity: CatalogIdentity? = identity(),
        inspection: PatchTargetInspection? = inspection(),
        installedGameVersion: String? = manifest.gameVersion,
        latest: ReleaseEntry? = release(),
        candidates: List<ReleaseEntry> = listOf(release()),
    ) = resolvePatchInstallationStatus(identity, inspection, manifest, installedGameVersion, latest, candidates)
}
