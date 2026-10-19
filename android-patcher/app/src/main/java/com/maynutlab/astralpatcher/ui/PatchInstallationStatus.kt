package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.CatalogIdentity
import com.maynutlab.astralpatcher.core.PatchManifest
import com.maynutlab.astralpatcher.core.PatchTargetInspection
import com.maynutlab.astralpatcher.core.ReleaseEntry

internal data class PatchInstallationStatus(
    val label: String,
    val description: String,
    val indicator: StatusIndicator,
    val installedPatchVersion: String? = null,
)

/** File observations establish installation state; history alone never proves installation. */
internal fun resolvePatchInstallationStatus(
    identity: CatalogIdentity?,
    inspection: PatchTargetInspection?,
    manifest: PatchManifest?,
    installedGameVersion: String?,
    latestRelease: ReleaseEntry?,
    releaseCandidates: List<ReleaseEntry>,
    unavailableReason: String? = null,
    error: String? = null,
): PatchInstallationStatus {
    if (!unavailableReason.isNullOrBlank()) {
        return PatchInstallationStatus("한글패치 확인 대기", unavailableReason, StatusIndicator.NEUTRAL)
    }
    val update = requiredGameUpdate(installedGameVersion, latestRelease)
    if (inspection == null || manifest == null) {
        if (!error.isNullOrBlank()) {
            return PatchInstallationStatus("한글패치 확인 실패", error, StatusIndicator.ERROR)
        }
        if (update != null) return awaitingGameUpdate(update)
        return unobservedPatchStatus(identity)
    }
    val files = inspection.files
    if (inspection.unreadable > 0 || files.any { it.state == "UNREADABLE" }) {
        return PatchInstallationStatus(
            "한글패치 확인 실패", "대상 파일을 읽을 수 없습니다. Shizuku 권한을 확인한 뒤 다시 시도해 주세요.",
            StatusIndicator.ERROR,
        )
    }
    if (inspection.ambiguous > 0 || files.any { it.state == "AMBIGUOUS" || it.candidates.size > 1 } ||
        files.map { it.path }.distinct().size != files.size ||
        files.map { it.actualPath }.filter { it.isNotBlank() }.let { it.distinct().size != it.size }
    ) {
        return PatchInstallationStatus(
            "한글패치 경로 확인 필요", "대상 파일의 경로가 여러 개입니다. 중복 리소스를 확인한 뒤 다시 시도해 주세요.",
            StatusIndicator.ERROR,
        )
    }
    val missing = maxOf(inspection.missing, files.count { it.state == "MISSING" })
    if (missing > 0) {
        if (update != null) return awaitingGameUpdate(update)
        return PatchInstallationStatus(
            "한글패치 설치 준비 필요", "대상 파일 ${missing}개가 없습니다. 게임에서 리소스를 모두 다운로드한 뒤 다시 시도해 주세요.",
            StatusIndicator.ERROR,
        )
    }
    if (files.any { it.error.isNotBlank() }) {
        return PatchInstallationStatus(
            "한글패치 확인 실패", "대상 파일을 확인하지 못했습니다. 접근 권한과 실행 로그를 확인해 주세요.",
            StatusIndicator.ERROR,
        )
    }
    if (files.isEmpty()) {
        if (!error.isNullOrBlank()) {
            return PatchInstallationStatus("한글패치 확인 실패", error, StatusIndicator.ERROR)
        }
        if (update != null) return awaitingGameUpdate(update)
        return unobservedPatchStatus(identity)
    }
    val observed = files.associateBy { it.path }
    if (manifest.files.isEmpty() || inspection.total != manifest.files.size || files.size != manifest.files.size ||
        manifest.files.map { it.relativePath }.toSet().let { it.size != manifest.files.size || it != observed.keys } ||
        !inspection.canWrite || inspection.planJson.isNullOrBlank()
    ) {
        return PatchInstallationStatus(
            "한글패치 확인 필요", "대상 파일과 설치 경로를 모두 확인하지 못했습니다. 다시 확인해 주세요.",
            StatusIndicator.ERROR,
        )
    }
    // The same file can belong to both source and payload. Original wins if ALL files are shared.
    val contents = patchContents(PatchStatusObservation(manifest, inspection))
    if (contents != PatchContents.UNKNOWN &&
        (!inspection.catalogMatches || installedGameVersion != manifest.gameVersion)
    ) {
        return PatchInstallationStatus(
            "한글패치 확인 실패", "현재 게임의 리소스와 검사한 배포가 일치하는지 확인하지 못했습니다.",
            StatusIndicator.ERROR,
        )
    }
    if (contents == PatchContents.ORIGINAL) {
        // Installation and restore history do not override the actual unpatched files.
        return PatchInstallationStatus(
            "한글패치 미설치", if (update != null) gameUpdatePatchDescription(update)
                else "설치 버튼을 누르면 최신 한글패치 리소스를 적용합니다", StatusIndicator.NEUTRAL,
        )
    }
    if (contents == PatchContents.PATCHED) {
        val routeReleases = releaseCandidates.filter { it.route == manifest.route }
        if (latestRelease != null && matchesRelease(manifest, latestRelease) &&
            routeReleases.firstOrNull()?.copy(uploadedAt = null) == latestRelease.copy(uploadedAt = null) &&
            routeReleases.count { matchesRelease(manifest, it) } == 1
        ) {
            return PatchInstallationStatus(
                "최신 한글패치 설치 완료", manifest.patchVersion, StatusIndicator.COMPLETED, manifest.patchVersion,
            )
        }
        val newer = newerPatchRelease(manifest, latestRelease, releaseCandidates)
        if (newer != null) {
            return PatchInstallationStatus(
                "한글패치 업데이트 필요", "설치됨 ${manifest.patchVersion} · 최신 ${newer.patchVersion}" +
                    update?.let { "\n${gameUpdatePatchDescription(it)}" }.orEmpty(),
                StatusIndicator.PENDING, manifest.patchVersion,
            )
        }
        return PatchInstallationStatus(
            "한글패치 버전 확인 실패", "패치 파일은 확인했지만 최신 배포와의 버전 비교를 완료하지 못했습니다.",
            StatusIndicator.ERROR,
        )
    }
    if (update != null && error.isNullOrBlank()) return awaitingGameUpdate(update)
    return PatchInstallationStatus(
        "한글패치 확인 필요", "파일이 원본 또는 확인된 한글패치와 일치하지 않습니다. 실행 로그를 확인해 주세요.",
        StatusIndicator.ERROR,
    )
}

private fun gameUpdatePatchDescription(update: GameUpdateRequirement): String =
    "최신 한글패치를 설치하려면 게임을 ${update.requiredVersion}으로 업데이트해 주세요."

private fun awaitingGameUpdate(update: GameUpdateRequirement): PatchInstallationStatus =
    PatchInstallationStatus("한글패치 확인 대기", gameUpdatePatchDescription(update), StatusIndicator.NEUTRAL)

private fun unobservedPatchStatus(identity: CatalogIdentity?): PatchInstallationStatus =
    if (identity?.historyStatus == "CORRUPT" || !identity?.historyError.isNullOrBlank()) {
        PatchInstallationStatus(
            "한글패치 이력 확인 필요", "설치 이력을 읽지 못했습니다. 실제 리소스를 확인해야 합니다.", StatusIndicator.ERROR,
        )
    } else {
        PatchInstallationStatus(
            "한글패치 확인 실패", "대상 파일의 관측 결과가 없습니다. 리소스를 다시 확인해 주세요.", StatusIndicator.ERROR,
        )
    }

internal fun matchesRelease(manifest: PatchManifest, release: ReleaseEntry): Boolean =
    manifest.route == release.route && manifest.gameVersion == release.gameVersion &&
        manifest.revision == release.revision && manifest.patchVersion == release.patchVersion &&
        manifest.catalogHash.equals(release.catalogHash, ignoreCase = true)

/** Route-wide newest-first order, including a latest release targeting a newer game version. */
private fun newerPatchRelease(
    manifest: PatchManifest,
    latestRelease: ReleaseEntry?,
    candidates: List<ReleaseEntry>,
): ReleaseEntry? {
    if (latestRelease == null || latestRelease.route != manifest.route) return null
    val routeReleases = candidates.filter { it.route == manifest.route }
    val installedIndex = routeReleases.indices.singleOrNull { matchesRelease(manifest, routeReleases[it]) } ?: return null
    val latestIndex = routeReleases.indices.singleOrNull {
        routeReleases[it].copy(uploadedAt = null) == latestRelease.copy(uploadedAt = null)
    } ?: return null
    return latestRelease.takeIf { latestIndex == 0 && latestIndex < installedIndex }
}
