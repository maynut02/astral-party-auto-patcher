package com.maynutlab.astralpatcher.shizuku

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only diagnosis. Discovery errors never become a misleading download requirement. */
internal fun inspectPlannedTargets(
    filesRoot: File,
    packageName: String,
    request: JSONObject,
    observedCatalogHash: String?,
    catalogWarnings: List<String>,
    catalogRecordsMatch: Boolean = true,
    findTargets: (String) -> Pair<List<File>, List<String>>,
): JSONObject {
    validateReleaseRequest(packageName, request)
    val files = request.getJSONArray("files")
    require(files.length() in 1..2048)
    val seen = mutableSetOf<String>()
    val observations = JSONArray()
    val targets = mutableListOf<PlannedTarget>()
    val warnings = catalogWarnings.toMutableList()
    val counts = mutableMapOf("ready" to 0, "missing" to 0, "incompatible" to 0, "unreadable" to 0, "ambiguous" to 0)
    for (index in 0 until files.length()) {
        val item = files.getJSONObject(index)
        val path = patchSafeRelative(item.getString("path"))
        require(seen.add(path)) { "검사 대상이 중복되었습니다." }
        val sourceSize = item.getLong("sourceSize").also { require(it > 0) }
        val payloadSize = item.getLong("payloadSize").also { require(it > 0) }
        val sourceSha = patchHex(item.getString("sourceSha256"), 64)
        val payloadSha = patchHex(item.getString("payloadSha256"), 64)
        val observation = JSONObject().put("path", path).put("actualPath", JSONObject.NULL)
            .put("size", JSONObject.NULL).put("sha256", JSONObject.NULL).put("error", JSONObject.NULL)
        var bucket = "unreadable"
        var state = "UNREADABLE"
        val candidatePaths = JSONArray()
        try {
            val (candidates, errors) = findTargets(path)
            val safeCandidates = candidates.map { candidate ->
                val relative = candidate.absoluteFile.relativeTo(filesRoot.absoluteFile).invariantSeparatorsPath
                patchChild(filesRoot, relative).also { candidatePaths.put(relative) }
            }.distinct()
            when {
                safeCandidates.size > 1 -> {
                    bucket = "ambiguous"; state = "AMBIGUOUS"
                    observation.put("error", "여러 캐시 파일이 같은 대상과 일치합니다.")
                }
                errors.isNotEmpty() && (safeCandidates.isNotEmpty() || errors.any { !it.startsWith("NOT_FOUND") }) ->
                    observation.put("error", errors.joinToString("; ").take(4096))
                safeCandidates.isEmpty() -> { bucket = "missing"; state = "MISSING" }
                else -> {
                    val target = safeCandidates.single()
                    val actual = target.relativeTo(filesRoot.canonicalFile).invariantSeparatorsPath
                    val beforeSize = target.length()
                    val beforeModified = target.lastModified()
                    val hash = patchSha(target)
                    require(target.isFile && target.length() == beforeSize && target.lastModified() == beforeModified) {
                        "검사 중 파일이 변경되었습니다. 다시 검사해 주세요."
                    }
                    observation.put("actualPath", actual).put("size", beforeSize).put("sha256", hash)
                    when {
                        beforeSize == sourceSize && hash == sourceSha -> { bucket = "ready"; state = "ORIGINAL" }
                        beforeSize == payloadSize && hash == payloadSha -> { bucket = "ready"; state = "CURRENT_PATCH" }
                        else -> {
                            bucket = "incompatible"; state = "UNKNOWN_MODIFIED"
                            warnings.add("알 수 없는 기존 파일을 교체합니다: $path")
                        }
                    }
                    targets.add(PlannedTarget(path, actual, beforeSize, hash, sourceSize, sourceSha, payloadSize, payloadSha))
                }
            }
        } catch (error: Exception) {
            bucket = "unreadable"; state = "UNREADABLE"
            observation.put("error", (error.message ?: error.javaClass.simpleName).take(4096))
        }
        counts[bucket] = counts.getValue(bucket) + 1
        observations.put(observation.put("state", state).put("candidates", candidatePaths))
    }
    val hashMatches = observedCatalogHash != null && observedCatalogHash == patchHex(request.getString("catalogHash"), 32)
    val catalogMatches = catalogRecordsMatch && hashMatches
    if (!hashMatches) warnings.add(if (observedCatalogHash == null) "로컬 catalog를 확인하지 못했습니다." else "선택한 릴리스와 로컬 catalog가 다릅니다.")
    val response = JSONObject().put("schemaVersion", 2).put("total", files.length()).put("files", observations)
        .put("observedCatalogHash", observedCatalogHash ?: JSONObject.NULL).put("catalogMatches", catalogMatches)
        .put("warnings", JSONArray(warnings.distinct()))
    counts.forEach { (key, count) -> response.put(key, count) }
    if (targets.size == files.length()) {
        val plan = JSONObject(request.toString()).removeAndReturn("files").put("schemaVersion", 1)
            .put("targets", JSONArray(targets.map { it.json() }))
            .put("observedCatalogHash", observedCatalogHash ?: JSONObject.NULL).put("packageName", packageName)
        response.put("plan", PlannedPatch.parse(packageName, plan.toString()).json())
    } else response.put("plan", JSONObject.NULL)
    return response
}

internal fun inspectPatchHistory(stateFile: File, observedCatalogHash: String?): JSONObject {
    var status = "NONE"
    var error: String? = null
    var patchVersion = ""
    var record: JSONObject? = null
    var text = ""
    try {
        if (java.nio.file.Files.exists(stateFile.toPath())) {
            require(stateFile.isFile && stateFile.length() <= 4 * 1024 * 1024L) { "설치 이력 크기 또는 경로가 올바르지 않습니다." }
            text = stateFile.readText(Charsets.UTF_8)
            record = JSONObject(text)
            if (!record.isNull("patchVersion")) {
                require(record.get("patchVersion") is String) { "설치 이력의 patchVersion 형식이 올바르지 않습니다." }
                patchVersion = record.getString("patchVersion").takeUnless { it.isBlank() }.orEmpty()
            }
            require(record.getInt("schemaVersion") in 1..2) { "지원하지 않는 설치 이력입니다." }
            require(record.get("catalogHash") is String) { "설치 이력의 catalogHash 형식이 올바르지 않습니다." }
            patchHex(record.getString("catalogHash"), 32)
            status = when {
                record.optString("operation") == "RESTORE" -> "RESTORED"
                record.optString("catalogHash") != observedCatalogHash -> "OTHER_CATALOG"
                else -> "OK"
            }
        } else {
            // exists() alone suppresses EACCES. An accessible parent distinguishes absence.
            val parent = stateFile.parentFile
            if (parent != null && parent.exists()) require(parent.listFiles() != null) { "설치 이력을 읽을 수 없습니다." }
        }
    } catch (failure: Exception) {
        status = "CORRUPT"
        error = (failure.message ?: failure.javaClass.simpleName).take(4096)
        if (patchVersion.isEmpty()) {
            val match = Regex("\"patchVersion\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")").find(text)
            patchVersion = match?.let { runCatching { JSONObject("{\"v\":${it.groupValues[1]}}").getString("v") }.getOrNull() }.orEmpty()
        }
    }
    return JSONObject().put("status", status).put("error", error ?: JSONObject.NULL)
        .put("patchVersion", patchVersion).put("record", record ?: JSONObject.NULL)
}

private fun JSONObject.removeAndReturn(key: String): JSONObject = apply { remove(key) }
