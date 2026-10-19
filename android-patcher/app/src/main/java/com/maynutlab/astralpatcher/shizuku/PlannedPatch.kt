package com.maynutlab.astralpatcher.shizuku

import com.maynutlab.astralpatcher.core.GameTarget
import com.maynutlab.astralpatcher.core.PatchProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal enum class PatchOperation { PATCH, RESTORE }

internal data class PlannedTarget(
    val path: String,
    val actualPath: String,
    val observedSize: Long,
    val observedSha256: String,
    val sourceSize: Long,
    val sourceSha256: String,
    val payloadSize: Long,
    val payloadSha256: String,
) {
    fun outputSize(operation: PatchOperation) = if (operation == PatchOperation.PATCH) payloadSize else sourceSize
    fun outputSha(operation: PatchOperation) = if (operation == PatchOperation.PATCH) payloadSha256 else sourceSha256
    fun json(): JSONObject = JSONObject()
        .put("path", path).put("actualPath", actualPath)
        .put("observedSize", observedSize).put("observedSha256", observedSha256)
        .put("sourceSize", sourceSize).put("sourceSha256", sourceSha256)
        .put("payloadSize", payloadSize).put("payloadSha256", payloadSha256)
}

internal data class PlannedPatch(
    val packageName: String,
    val operation: PatchOperation,
    val route: String,
    val gameVersion: String,
    val catalogHash: String,
    val patchVersion: String,
    val manifestSha256: String,
    val observedCatalogHash: String?,
    val targets: List<PlannedTarget>,
    val apkSnapshot: String?,
) {
    fun json(): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("packageName", packageName)
        .put("operation", operation.name).put("route", route).put("gameVersion", gameVersion)
        .put("catalogHash", catalogHash).put("patchVersion", patchVersion)
        .put("manifestSha256", manifestSha256)
        .put("observedCatalogHash", observedCatalogHash ?: JSONObject.NULL)
        .put("targets", JSONArray(targets.map { it.json() }))
        .put("apkSnapshot", apkSnapshot ?: JSONObject.NULL)

    companion object {
        fun parse(packageName: String, text: String): PlannedPatch {
            require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "작업 계획이 너무 큽니다." }
            val root = JSONObject(text)
            validateReleaseRequest(packageName, root)
            require(root.getInt("schemaVersion") == 1) { "지원하지 않는 작업 계획입니다." }
            require(root.has("observedCatalogHash")) { "관측한 catalog가 계획에 없습니다." }
            val items = root.getJSONArray("targets")
            require(items.length() in 1..2048) { "작업 대상 수가 올바르지 않습니다." }
            val targets = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                PlannedTarget(
                    patchSafeRelative(item.getString("path")), patchSafeRelative(item.getString("actualPath")),
                    item.getLong("observedSize").also { require(it >= 0) }, patchHex(item.getString("observedSha256"), 64),
                    item.getLong("sourceSize").also { require(it > 0) }, patchHex(item.getString("sourceSha256"), 64),
                    item.getLong("payloadSize").also { require(it > 0) }, patchHex(item.getString("payloadSha256"), 64),
                ).also {
                    val suffix = it.actualPath.split('/').takeLast(it.path.split('/').size).joinToString("/")
                    require(bundleAlias(suffix) == bundleAlias(it.path)) { "계획의 실제 경로가 요청한 번들과 다릅니다." }
                    require(it.actualPath.split('/').none { part -> part == ".astralpatch-manager" }) {
                        "패처 관리 파일은 수정할 수 없습니다."
                    }
                }
            }
            require(targets.map { it.path }.distinct().size == targets.size) { "작업 경로가 중복되었습니다." }
            require(targets.map { it.actualPath }.distinct().size == targets.size) { "실제 작업 경로가 중복되었습니다." }
            return PlannedPatch(
                packageName, PatchOperation.valueOf(root.getString("operation")), root.getString("route"),
                root.getString("gameVersion"), patchHex(root.getString("catalogHash"), 32), root.getString("patchVersion"),
                patchHex(root.getString("manifestSha256"), 64),
                if (root.isNull("observedCatalogHash")) null else patchHex(root.getString("observedCatalogHash"), 32),
                targets, snapshotString(root),
            )
        }
    }
}

internal fun validateReleaseRequest(packageName: String, root: JSONObject) {
    val game = requireNotNull(GameTarget.fromPackageName(packageName)) { "지원하지 않는 게임 패키지입니다." }
    require(root.getInt("schemaVersion") in 1..2) { "지원하지 않는 작업 계획입니다." }
    require(!root.has("packageName") || root.getString("packageName") == packageName) { "작업 패키지가 다릅니다." }
    require(root.getString("route") == game.route) { "작업 route가 게임 패키지와 다릅니다." }
    PatchOperation.valueOf(root.getString("operation"))
    listOf("gameVersion", "patchVersion").forEach { key ->
        require(root.getString(key).isNotBlank() && root.getString(key).length <= 256) { "$key 값이 올바르지 않습니다." }
    }
    patchHex(root.getString("catalogHash"), 32)
    patchHex(root.getString("manifestSha256"), 64)
    snapshotString(root)?.let { text ->
        val snapshot = JSONObject(text)
        require(!snapshot.has("packageName") || snapshot.getString("packageName") == packageName) { "APK 패키지가 다릅니다." }
        require(snapshot.optInt("userId", 0) == 0) { "보조 사용자 프로필은 지원하지 않습니다." }
        apkPaths(snapshot).forEach { path ->
            require(path.startsWith('/') && path.endsWith(".apk") && !path.contains('\u0000')) { "APK 경로가 올바르지 않습니다." }
        }
    }
}

internal fun snapshotString(root: JSONObject): String? =
    if (!root.has("apkSnapshot") || root.isNull("apkSnapshot")) null
    else root.getString("apkSnapshot").also { require(it.length <= 64 * 1024); JSONObject(it) }

internal fun apkPaths(snapshot: JSONObject?): List<String> {
    if (snapshot == null) return emptyList()
    val result = mutableListOf<String>()
    if (!snapshot.isNull("sourceDir")) snapshot.optString("sourceDir").takeIf { it.isNotBlank() }?.let(result::add)
    snapshot.optJSONArray("splitSourceDirs")?.let { splits ->
        require(splits.length() <= 64)
        for (index in 0 until splits.length()) result.add(splits.getString(index))
    }
    return result.distinct()
}

internal fun patchSafeRelative(value: String): String = PatchProtocol.safeRelativePath(value).also { safe ->
    require(safe.none { it == ':' || it.code < 32 || it.code == 127 }) { "안전하지 않은 파일 경로입니다." }
}

internal fun patchChild(root: File, relative: String): File {
    val safe = patchSafeRelative(relative)
    val base = root.canonicalFile
    val child = File(base, safe).canonicalFile
    require(child.path.startsWith(base.path + File.separator)) { "패키지 허용 경로를 벗어났습니다." }
    require(child.relativeTo(base).invariantSeparatorsPath == safe) { "심볼릭 링크를 통한 경로 변경은 허용하지 않습니다." }
    return child
}

internal fun patchHex(value: String, length: Int): String = value.lowercase(java.util.Locale.ROOT).also {
    require(it.length == length && it.all { char -> char in '0'..'9' || char in 'a'..'f' }) { "해시가 올바르지 않습니다." }
}

internal fun patchSha(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun patchVerify(file: File, size: Long, sha: String) {
    require(file.isFile && file.length() == size && patchSha(file) == sha) { "파일이 검사 이후 변경되었거나 검증에 실패했습니다: $file" }
}

private fun bundleAlias(path: String): String = if (path.endsWith("/__data__")) path.dropLast(2) else path
