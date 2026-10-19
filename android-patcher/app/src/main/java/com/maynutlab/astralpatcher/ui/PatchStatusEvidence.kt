package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.CatalogIdentity
import com.maynutlab.astralpatcher.core.PatchManifest
import com.maynutlab.astralpatcher.core.PatchTargetInspection
import com.maynutlab.astralpatcher.core.ReleaseEntry

internal data class PatchStatusObservation(val manifest: PatchManifest, val inspection: PatchTargetInspection)

internal enum class PatchContents { ORIGINAL, PATCHED, UNKNOWN }

/** Compare observed bytes against authenticated manifests, never an installation-history label. */
internal fun patchContents(observation: PatchStatusObservation): PatchContents {
    val (manifest, inspection) = observation
    val expected = manifest.files
    val files = inspection.files
    if (expected.isEmpty() || expected.size != inspection.total || files.size != expected.size ||
        !inspection.canWrite || inspection.planJson.isNullOrBlank() ||
        expected.map { it.relativePath }.distinct().size != expected.size ||
        files.map { it.path }.toSet() != expected.map { it.relativePath }.toSet()
    ) return PatchContents.UNKNOWN
    val observed = files.associateBy { it.path }
    val original = expected.all {
        val file = observed.getValue(it.relativePath)
        file.size == it.sourceSize && file.sha256.equals(it.sourceSha256, ignoreCase = true)
    }
    if (original) return PatchContents.ORIGINAL
    val patched = expected.all {
        val file = observed.getValue(it.relativePath)
        file.size == it.payloadSize && file.sha256.equals(it.payloadSha256, ignoreCase = true)
    }
    return if (patched) PatchContents.PATCHED else PatchContents.UNKNOWN
}

/** Older manifests are metadata-only evidence. They never change the latest operation target. */
internal fun selectPatchStatusObservation(
    primary: PatchStatusObservation,
    compatible: PatchStatusObservation?,
    identity: CatalogIdentity?,
    candidates: List<ReleaseEntry>,
    load: (ReleaseEntry) -> PatchStatusObservation,
    onFailure: (ReleaseEntry, Exception) -> Unit = { _, _ -> },
): PatchStatusObservation {
    val primaryContents = if (primary.inspection.catalogMatches) patchContents(primary) else PatchContents.UNKNOWN
    if (primaryContents == PatchContents.PATCHED) return primary
    val baseline = compatible ?: primary
    val baselineContents = if (compatible == null) primaryContents
        else if (compatible.inspection.catalogMatches) patchContents(compatible) else PatchContents.UNKNOWN
    if (baselineContents != PatchContents.UNKNOWN) return baseline
    if (identity == null || identity.gameVersion.isBlank() || identity.catalogHash.isBlank()) return baseline
    val eligible = candidates.filter {
        it.route == primary.manifest.route && it.gameVersion == identity.gameVersion &&
            it.catalogHash.equals(identity.catalogHash, ignoreCase = true) &&
            !matchesRelease(primary.manifest, it) &&
            (compatible == null || !matchesRelease(compatible.manifest, it))
    }
    // An orphan cache from another resource catalog must not establish an installed older patch.
    val ordered = eligible.filter { it.patchVersion == identity.installedPatchVersion } +
        eligible.filter { it.patchVersion != identity.installedPatchVersion }
    for (release in ordered.take(MAX_OLDER_MANIFESTS)) {
        val observation = try {
            load(release)
        } catch (error: Exception) {
            onFailure(release, error)
            break
        }
        require(matchesRelease(observation.manifest, release)) { "설치 상태 검사 manifest가 배포와 다릅니다." }
        if (observation.inspection.catalogMatches && patchContents(observation) == PatchContents.PATCHED) return observation
    }
    return baseline
}

private const val MAX_OLDER_MANIFESTS = 8
