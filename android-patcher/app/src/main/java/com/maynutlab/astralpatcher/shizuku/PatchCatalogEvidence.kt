package com.maynutlab.astralpatcher.shizuku

import org.json.JSONArray

/** Only a fully decoded selected catalog can establish that a bundle record is absent. */
internal fun patchCatalogEvidence(catalog: CatalogObservation?, files: JSONArray): Pair<Boolean, List<String>> {
    val selected = catalog?.candidates?.singleOrNull { it.version == catalog.selectedVersion && it.hash == catalog.selectedHash }
    if (selected == null || !selected.bundleRecordsComplete) {
        return true to listOf("CATALOG_BUNDLE_RECORDS_UNCONFIRMED: 카탈로그의 전체 번들 목록을 확인하지 못했습니다.")
    }
    val records = selected.bundles.map { it.bundleName to it.cacheHash }.toSet()
    val missing = (0 until files.length()).map { files.getJSONObject(it).getString("path") }.filter { path ->
        val parts = patchSafeRelative(path).split('/')
        parts.size != 3 || (parts[0] to parts[1]) !in records
    }
    return missing.isEmpty() to missing.map { "CATALOG_TARGET_MISMATCH: 선택한 카탈로그에 요청한 번들 기록이 없습니다: $it" }
}
