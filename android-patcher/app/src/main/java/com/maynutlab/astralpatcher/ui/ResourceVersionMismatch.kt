package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.PatchTargetInspection
import com.maynutlab.astralpatcher.core.ReleaseEntry

/** Confirmation is for an observed resource-version difference, not content or diagnostic warnings. */
internal fun resourceVersionMismatch(
    inspection: PatchTargetInspection,
    installedGameVersion: String?,
    latestRelease: ReleaseEntry,
): String? {
    require(inspection.canWrite && inspection.planJson != null && inspection.files.size == inspection.total) {
        "모든 대상 파일을 확인한 뒤 리소스 버전을 비교해야 합니다."
    }
    val differentGame = !installedGameVersion.isNullOrBlank() && installedGameVersion != latestRelease.gameVersion
    val differentCatalog = !inspection.catalogHash.isNullOrBlank() &&
        !inspection.catalogHash.equals(latestRelease.catalogHash, ignoreCase = true)
    return if (differentGame || differentCatalog) {
        "설치된 리소스와 최신 한글패치의 대상 리소스가 다릅니다. 게임에서 최신 리소스를 다운로드했는지 확인해 주세요."
    } else null
}
