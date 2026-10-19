package com.maynutlab.astralpatcher.core

import org.json.JSONObject
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
const val PATCH_CHANNEL = "release"
const val RELEASE_INDEX_URL =
    "https://raw.githubusercontent.com/maynut02/astral-party-korean-patch/distribution/release-index.json"
const val SHIZUKU_RELEASE_API =
    "https://api.github.com/repos/thedjchi/Shizuku/releases/latest"
const val MOBILE_PATCHER_INDEX_URL =
    "https://raw.githubusercontent.com/maynut02/astral-party-auto-patcher/distribution/mobile-patcher-index.json"
const val ORIGINAL_GAME_INDEX_URL =
    "https://raw.githubusercontent.com/maynut02/astral-party-auto-patcher/distribution/android-game-index.json"

data class CatalogIdentity(
    val gameVersion: String,
    val catalogHash: String,
    val installedPatchVersion: String?,
    val historyStatus: String = "UNKNOWN",
    val historyError: String = "",
    val diagnostic: String = "",
)

data class ReleaseEntry(
    val route: String,
    val gameVersion: String,
    val revision: String,
    val catalogHash: String,
    val patchVersion: String,
    val manifestUrl: String,
    val manifestSha256: String,
    val uploadedAt: String? = null,
)

data class PatchFile(
    val relativePath: String,
    val downloadUrl: String,
    val downloadSha256: String,
    val downloadSize: Long,
    val payloadSha256: String,
    val payloadSize: Long,
    val sourceDownloadUrl: String,
    val sourceDownloadSha256: String,
    val sourceDownloadSize: Long,
    val sourceSha256: String,
    val sourceSize: Long,
)

data class PatchManifest(
    val route: String,
    val patchVersion: String,
    val gameVersion: String,
    val revision: String,
    val catalogHash: String,
    val files: List<PatchFile>,
)

data class PatchTargetDiagnostic(
    val path: String,
    val actualPath: String,
    val size: Long?,
    val sha256: String,
    val state: String,
    val error: String = "",
    val candidates: List<String> = emptyList(),
) {
    val isKnown: Boolean
        get() = state in setOf("ORIGINAL", "CURRENT_PATCH", "KNOWN_OLDER_PATCH")

    val canWrite: Boolean
        get() = (isKnown || state == "UNKNOWN_MODIFIED") && actualPath.isNotBlank() &&
            size != null && size >= 0 && sha256.matches(Regex("[0-9a-fA-F]{64}")) &&
            error.isBlank() && candidates.size <= 1 &&
            (candidates.isEmpty() || candidates.single() == actualPath)
}

data class PatchTargetInspection(
    val total: Int,
    val ready: Int,
    val missing: Int,
    val incompatible: Int,
    val files: List<PatchTargetDiagnostic> = emptyList(),
    val catalogHash: String? = null,
    val catalogMatches: Boolean = true,
    val planJson: String? = null,
    val unreadable: Int = 0,
    val ambiguous: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    val isReady: Boolean
        get() = catalogMatches && total > 0 && ready == total && missing == 0 &&
            incompatible == 0 && unreadable == 0 && ambiguous == 0 &&
            (files.isEmpty() || (canWrite && files.all { it.isKnown }))

    val canWrite: Boolean
        get() = total > 0 && ready >= 0 && incompatible >= 0 && missing == 0 &&
            unreadable == 0 && ambiguous == 0 && ready.toLong() + incompatible == total.toLong() &&
            // Legacy summaries prove readability only for READY files.
            (if (files.isEmpty()) ready == total else files.size == total &&
                files.all { it.canWrite } && files.map { it.path }.distinct().size == total &&
                files.map { it.actualPath }.distinct().size == total)
}

data class PatchApplyResult(
    val ok: Boolean,
    val error: String,
)

data class PatchDiagnosticEvent(
    val timestampMs: Long,
    val elapsedRealtimeNanos: Long,
    val pid: Int,
    val uid: Int,
    val thread: String,
    val stage: String,
    val detail: String,
)

data class PatchTransactionSnapshot(
    val exists: Boolean,
    val path: String,
    val catalogExists: Boolean,
    val catalogBytes: Long,
    val journalExists: Boolean,
    val journalBytes: Long,
    val journalEntries: Int,
    val journalReadError: String,
    val previousFiles: Int,
    val stagingFiles: Int,
)

data class PatchDiagnostics(
    val serviceInfo: String,
    val transactionId: String,
    val events: List<PatchDiagnosticEvent>,
    val transaction: PatchTransactionSnapshot,
    val diagnosticsFileExists: Boolean,
    val diagnosticsFileBytes: Long,
    val readError: String,
)

object PatchProtocol {
    fun parseInspection(json: String): CatalogIdentity {
        val root = JSONObject(json)
        val schema = root.getInt("schemaVersion")
        require(schema in 1..2) { "지원하지 않는 게임 진단 결과입니다." }
        val catalogHash = if (schema == 1) root.getString("catalogHash")
            else nullableString(root, "catalogHash").orEmpty()
        return CatalogIdentity(
            gameVersion = if (schema == 1) root.getString("gameVersion")
                else nullableString(root, "gameVersion").orEmpty(),
            catalogHash = if (schema == 2 && catalogHash.isEmpty()) ""
                else requireHex(catalogHash, 32, "catalog hash"),
            installedPatchVersion = nullableString(root, "patchVersion"),
            historyStatus = nullableString(root, "historyStatus") ?: "UNKNOWN",
            historyError = nullableString(root, "historyError").orEmpty(),
            diagnostic = inspectionDiagnostic(root),
        )
    }

    /** Newest first, restricted to the requested route/channel and optional exact APK version.
     * Call again without gameVersion if an empty APK-version list needs route-wide fallback.
     * Entries retain their index-provided manifest digest; duplicates are never coalesced.
     */
    fun releasesForRoute(json: String, route: String, gameVersion: String? = null): List<ReleaseEntry> {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "지원하지 않는 release index입니다." }
        val releases = root.getJSONArray("releases")
        val matches = buildList {
            for (index in 0 until releases.length()) {
                val item = releases.getJSONObject(index)
                if (item.getString("route") != route || item.getString("channel") != PATCH_CHANNEL) continue
                if (gameVersion != null && item.getString("gameVersion") != gameVersion) continue
                val manifestUrl = item.getString("manifestUrl")
                TrustedUrls.requireReleaseAsset(manifestUrl)
                add(
                    ReleaseEntry(
                        route = route,
                        gameVersion = item.getString("gameVersion"),
                        revision = item.getString("revision").also {
                            require(it.isNotBlank()) { "게임 revision이 비어 있습니다." }
                        },
                        catalogHash = requireHex(item.getString("catalogHash"), 32, "catalog hash"),
                        patchVersion = item.getString("patchVersion").also {
                            require(it.isNotBlank()) { "patch version이 비어 있습니다." }
                        },
                        uploadedAt = PatchUploadTimeProtocol.normalizeTimestamp(item.opt("uploadedAt") as? String),
                        manifestUrl = manifestUrl,
                        manifestSha256 = requireHex(item.getString("manifestSha256"), 64, "manifest SHA-256"),
                    )
                )
            }
        }
        val identities = mutableSetOf<List<String>>()
        matches.forEach { release ->
            check(identities.add(listOf(release.gameVersion, release.revision, release.patchVersion))) {
                "정식 Android patch가 중복되어 선택이 모호합니다: ${release.patchVersion}"
            }
        }
        return matches.sortedWith(
            Comparator<ReleaseEntry>(::compareReleaseOrder)
                .thenBy { it.gameVersion }.thenBy { it.revision }.thenBy { it.patchVersion }
                .thenBy { it.catalogHash }.thenBy { it.manifestSha256 }.reversed()
        )
    }

    fun latestReleaseForRoute(json: String, route: String, gameVersion: String? = null): ReleaseEntry? =
        selectLatest(releasesForRoute(json, route, gameVersion))

    fun compatibleReleases(json: String, catalog: CatalogIdentity, route: String): List<ReleaseEntry> {
        if (catalog.gameVersion.isBlank() || catalog.catalogHash.isBlank()) return emptyList()
        val hash = requireHex(catalog.catalogHash, 32, "catalog hash")
        return releasesForRoute(json, route, catalog.gameVersion).filter { it.catalogHash == hash }
    }

    fun resolveRelease(json: String, catalog: CatalogIdentity, route: String): ReleaseEntry? {
        val matches = compatibleReleases(json, catalog, route)
        check(matches.map { it.revision }.distinct().size <= 1) {
            "현재 리소스에 일치하는 배포 정보가 모호합니다. 배포 인덱스를 확인해 주세요."
        }
        return selectLatest(matches)
    }

    fun parseManifest(bytes: ByteArray, release: ReleaseEntry, expectedRoute: String): PatchManifest {
        require(sha256(bytes) == release.manifestSha256) {
            "manifest SHA-256이 release index와 일치하지 않습니다."
        }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == 2) { "지원하지 않는 patch manifest입니다." }
        val patch = root.getJSONObject("patch")
        val game = root.getJSONObject("game")
        require(release.route == expectedRoute) { "release route가 선택한 게임과 다릅니다." }
        require(patch.getString("route") == expectedRoute) { "선택한 게임용 patch가 아닙니다." }
        require(patch.getString("channel") == PATCH_CHANNEL) { "정식 patch가 아닙니다." }
        require(patch.getString("version") == release.patchVersion) { "patch version이 다릅니다." }
        require(game.getString("version") == release.gameVersion) { "게임 버전이 다릅니다." }
        require(game.getString("revision") == release.revision) { "게임 revision이 다릅니다." }
        require(requireHex(game.getString("catalogHash"), 32, "catalog hash") == release.catalogHash) {
            "catalog hash가 다릅니다."
        }

        val rawFiles = root.getJSONArray("files")
        require(rawFiles.length() > 0) { "patch 파일이 없습니다." }
        require(rawFiles.length() <= MAX_PATCH_FILES) { "patch 파일 수가 허용 범위를 초과합니다." }
        val identities = mutableSetOf<String>()
        val files = buildList {
            for (index in 0 until rawFiles.length()) {
                val item = rawFiles.getJSONObject(index)
                require(item.getString("target") == "addressables") {
                    "Android Patch Manager는 APK 내부 파일을 수정하지 않습니다."
                }
                require(item.getString("operation") == "replace") { "지원하지 않는 patch 작업입니다." }
                require(item.getString("compression") == "gzip") { "지원하지 않는 압축 형식입니다." }
                val path = safeRelativePath(item.getString("path"))
                require(identities.add(path)) { "중복된 patch 경로입니다: $path" }
                val downloadUrl = item.getString("downloadUrl")
                TrustedUrls.requireReleaseAsset(downloadUrl)
                val sourceDownloadUrl = item.getString("sourceDownloadUrl")
                TrustedUrls.requireReleaseAsset(sourceDownloadUrl)
                add(
                    PatchFile(
                        relativePath = path,
                        downloadUrl = downloadUrl,
                        downloadSha256 = requireHex(
                            item.getString("downloadSha256"), 64, "download SHA-256"
                        ),
                        downloadSize = positive(item.getLong("downloadSize"), "download size"),
                        payloadSha256 = requireHex(item.getString("sha256"), 64, "payload SHA-256"),
                        payloadSize = positive(item.getLong("size"), "payload size"),
                        sourceDownloadUrl = sourceDownloadUrl,
                        sourceDownloadSha256 = requireHex(
                            item.getString("sourceDownloadSha256"), 64, "source download SHA-256"
                        ),
                        sourceDownloadSize = positive(
                            item.getLong("sourceDownloadSize"), "source download size"
                        ),
                        sourceSha256 = requireHex(
                            item.getString("sourceSha256"), 64, "source SHA-256"
                        ),
                        sourceSize = positive(item.getLong("sourceSize"), "source size"),
                    )
                )
            }
        }
        return PatchManifest(
            route = expectedRoute,
            patchVersion = release.patchVersion,
            gameVersion = release.gameVersion,
            revision = release.revision,
            catalogHash = release.catalogHash,
            files = files,
        )
    }

    fun createTargetInspectionRequest(
        manifest: PatchManifest,
        release: ReleaseEntry? = null,
        operation: String = "PATCH",
        apkSnapshot: String? = null,
    ): String {
        require(manifest.files.isNotEmpty() && manifest.files.size <= MAX_PATCH_FILES) {
            "검사할 patch 파일 수가 올바르지 않습니다."
        }
        require(operation == "PATCH" || operation == "RESTORE") { "지원하지 않는 리소스 작업입니다." }
        require(manifest.patchVersion.isNotBlank() && manifest.gameVersion.isNotBlank() &&
            manifest.route.isNotBlank()) { "검사할 patch 정보가 비어 있습니다." }
        val catalogHash = requireHex(manifest.catalogHash, 32, "catalog hash")
        if (release != null) {
            require(release.route == manifest.route && release.gameVersion == manifest.gameVersion &&
                release.revision == manifest.revision && release.catalogHash == catalogHash &&
                release.patchVersion == manifest.patchVersion) {
                "선택한 release가 검사할 manifest와 다릅니다."
            }
        }
        val manifestSha256 = release?.let { requireHex(it.manifestSha256, 64, "manifest SHA-256") }
        val paths = mutableSetOf<String>()
        val files = org.json.JSONArray()
        manifest.files.forEach { item ->
            val path = safeRelativePath(item.relativePath)
            require(paths.add(path)) { "중복된 patch 경로입니다: $path" }
            files.put(
                JSONObject()
                    .put("path", path)
                    .put("sourceSize", positive(item.sourceSize, "source size"))
                    .put("sourceSha256", requireHex(item.sourceSha256, 64, "source SHA-256"))
                    .put("payloadSize", positive(item.payloadSize, "payload size"))
                    .put("payloadSha256", requireHex(item.payloadSha256, 64, "payload SHA-256"))
            )
        }
        val request = JSONObject()
            .put("schemaVersion", 2)
            .put("route", manifest.route)
            .put("gameVersion", manifest.gameVersion)
            .put("catalogHash", catalogHash)
            .put("patchVersion", manifest.patchVersion)
            .put("manifestSha256", manifestSha256.orEmpty())
            .put("operation", operation)
            .put("files", files)
            .put("apkSnapshot", apkSnapshot ?: JSONObject.NULL)
            .toString()
        require(request.toByteArray(Charsets.UTF_8).size <= MAX_INSPECTION_REQUEST_BYTES) {
            "리소스 검사 요청이 허용 크기를 초과합니다."
        }
        return request
    }

    fun parseTargetInspection(json: String): PatchTargetInspection {
        val root = JSONObject(json)
        val schema = root.getInt("schemaVersion")
        require(schema in 1..2) { "지원하지 않는 리소스 검사 결과입니다." }
        val files = if (schema == 1) emptyList() else {
            val rawFiles = root.getJSONArray("files")
            require(rawFiles.length() in 1..MAX_PATCH_FILES) { "리소스 검사 대상 수가 올바르지 않습니다." }
            List(rawFiles.length()) { index ->
                val item = rawFiles.getJSONObject(index)
                val state = item.getString("state")
                require(state in TARGET_STATES) { "지원하지 않는 리소스 파일 상태입니다: $state" }
                PatchTargetDiagnostic(
                    path = safeRelativePath(item.getString("path")),
                    actualPath = nullableString(item, "actualPath").orEmpty(),
                    size = if (item.isNull("size")) null else item.getLong("size").also {
                        require(it >= 0) { "리소스 크기가 올바르지 않습니다." }
                    },
                    sha256 = nullableString(item, "sha256")?.let {
                        requireHex(it, 64, "observed SHA-256")
                    }.orEmpty(),
                    state = state,
                    error = nullableString(item, "error").orEmpty(),
                    candidates = parseStrings(item, "candidates"),
                )
            }
        }
        val catalogHash = if (schema == 1) null else nullableString(root, "observedCatalogHash")
            ?.let { requireHex(it, 32, "observed catalog hash") }
        val result = PatchTargetInspection(
            total = root.getInt("total"),
            ready = root.getInt("ready"),
            missing = root.getInt("missing"),
            incompatible = root.getInt("incompatible"),
            files = files,
            catalogHash = catalogHash,
            catalogMatches = if (schema == 1) true else root.getBoolean("catalogMatches"),
            planJson = if (root.isNull("plan")) null else root.getJSONObject("plan").toString(),
            unreadable = if (schema == 1) 0 else root.getInt("unreadable"),
            ambiguous = if (schema == 1) 0 else root.getInt("ambiguous"),
            warnings = parseStrings(root, "warnings"),
        )
        require(result.total in 1..MAX_PATCH_FILES) { "리소스 검사 대상 수가 올바르지 않습니다." }
        require(result.ready >= 0 && result.missing >= 0 && result.incompatible >= 0 &&
            result.unreadable >= 0 && result.ambiguous >= 0) {
            "리소스 검사 결과에 음수 값이 있습니다."
        }
        require(result.ready.toLong() + result.missing + result.incompatible + result.unreadable +
            result.ambiguous == result.total.toLong()) {
            "리소스 검사 결과 합계가 올바르지 않습니다."
        }
        if (schema == 2) {
            require(files.size == result.total && files.count { it.isKnown } == result.ready &&
                files.count { it.state == "MISSING" } == result.missing &&
                files.count { it.state == "UNKNOWN_MODIFIED" } == result.incompatible &&
                files.count { it.state == "UNREADABLE" } == result.unreadable &&
                files.count { it.state == "AMBIGUOUS" } == result.ambiguous) {
                "리소스 파일 진단과 검사 합계가 다릅니다."
            }
            require(!result.catalogMatches || catalogHash != null) { "일치하는 카탈로그 정보가 없습니다." }
        }
        return result
    }

    fun createApplyFileResult(error: Throwable? = null): String {
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("ok", error == null)
        if (error != null) {
            val detail = buildString {
                append(error.javaClass.name)
                error.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
            }.take(MAX_DIAGNOSTIC_DETAIL_CHARS)
            root.put("error", detail)
        }
        return root.toString()
    }

    fun parseApplyFileResult(json: String): PatchApplyResult {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "지원하지 않는 파일 적용 결과입니다." }
        val ok = root.getBoolean("ok")
        val error = limitedText(root.optString("error"), MAX_DIAGNOSTIC_DETAIL_CHARS, "파일 적용 오류")
        require(ok || error.isNotBlank()) { "실패한 파일 적용 결과에 오류 정보가 없습니다." }
        return PatchApplyResult(ok = ok, error = error)
    }

    fun parsePatchDiagnostics(json: String, expectedTransactionId: String): PatchDiagnostics {
        require(expectedTransactionId.matches(Regex("^[0-9a-f-]{36}$"))) {
            "transaction ID가 올바르지 않습니다."
        }
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "지원하지 않는 patch 진단 결과입니다." }
        require(root.getString("transactionId") == expectedTransactionId) {
            "patch 진단 transaction ID가 다릅니다."
        }
        val rawEvents = root.getJSONArray("events")
        require(rawEvents.length() <= MAX_DIAGNOSTIC_EVENTS) { "patch 진단 이벤트가 너무 많습니다." }
        val events = buildList {
            for (index in 0 until rawEvents.length()) {
                val event = rawEvents.getJSONObject(index)
                require(event.getString("transactionId") == expectedTransactionId) {
                    "patch 진단 이벤트의 transaction ID가 다릅니다."
                }
                add(
                    PatchDiagnosticEvent(
                        timestampMs = event.getLong("timestampMs").also { require(it > 0) },
                        elapsedRealtimeNanos = event.getLong("elapsedRealtimeNanos").also { require(it >= 0) },
                        pid = event.getInt("pid").also { require(it > 0) },
                        uid = event.getInt("uid").also { require(it >= 0) },
                        thread = limitedText(event.getString("thread"), 128, "진단 thread"),
                        stage = limitedText(event.getString("stage"), 128, "진단 stage"),
                        detail = limitedText(event.optString("detail"), MAX_DIAGNOSTIC_DETAIL_CHARS, "진단 detail"),
                    )
                )
            }
        }
        val rawTransaction = root.getJSONObject("transaction")
        val transaction = PatchTransactionSnapshot(
            exists = rawTransaction.getBoolean("exists"),
            path = limitedText(rawTransaction.getString("path"), MAX_DIAGNOSTIC_DETAIL_CHARS, "transaction path"),
            catalogExists = rawTransaction.getBoolean("catalogExists"),
            catalogBytes = rawTransaction.getLong("catalogBytes"),
            journalExists = rawTransaction.getBoolean("journalExists"),
            journalBytes = rawTransaction.getLong("journalBytes"),
            journalEntries = rawTransaction.getInt("journalEntries"),
            journalReadError = limitedText(
                rawTransaction.optString("journalReadError"),
                MAX_DIAGNOSTIC_DETAIL_CHARS,
                "journal 오류",
            ),
            previousFiles = rawTransaction.getInt("previousFiles"),
            stagingFiles = rawTransaction.getInt("stagingFiles"),
        )
        require(
            transaction.catalogBytes >= 0 && transaction.journalBytes >= 0 &&
                transaction.journalEntries >= -1 && transaction.previousFiles >= 0 &&
                transaction.stagingFiles >= 0
        ) { "patch transaction 진단 값이 올바르지 않습니다." }
        return PatchDiagnostics(
            serviceInfo = limitedText(root.getString("serviceInfo"), 256, "service info"),
            transactionId = expectedTransactionId,
            events = events,
            transaction = transaction,
            diagnosticsFileExists = root.getBoolean("diagnosticsFileExists"),
            diagnosticsFileBytes = root.getLong("diagnosticsFileBytes").also { require(it >= 0) },
            readError = limitedText(root.optString("readError"), MAX_DIAGNOSTIC_DETAIL_CHARS, "진단 읽기 오류"),
        )
    }

    private fun selectLatest(releases: List<ReleaseEntry>): ReleaseEntry? {
        val latest = releases.firstOrNull() ?: return null
        check(releases.drop(1).none { compareReleaseOrder(latest, it) == 0 }) {
            "최신 정식 Android patch 정보가 모호합니다. 배포 인덱스를 확인해 주세요."
        }
        return latest
    }

    private fun compareReleaseOrder(left: ReleaseEntry, right: ReleaseEntry): Int {
        val versionOrder = compareVersionStrings(left.gameVersion, right.gameVersion)
        if (versionOrder != 0) return versionOrder
        // p is meaningful only inside the same metadata game version and revision.
        // In particular, the tag's r segment is NOT game.revision.
        if (left.gameVersion != right.gameVersion) return left.gameVersion.compareTo(right.gameVersion)
        val leftRevision = left.revision.removePrefix("r").toBigIntegerOrNull()
        val rightRevision = right.revision.removePrefix("r").toBigIntegerOrNull()
        val revisionOrder = if (leftRevision != null && rightRevision != null)
            leftRevision.compareTo(rightRevision) else left.revision.compareTo(right.revision)
        if (revisionOrder != 0) return revisionOrder
        if (left.revision != right.revision) return left.revision.compareTo(right.revision)
        return patchNumber(left.patchVersion).compareTo(patchNumber(right.patchVersion))
    }

    private fun patchNumber(version: String): BigInteger =
        Regex("_p([0-9]+)$").find(version)?.groupValues?.get(1)?.toBigInteger() ?: BigInteger.ZERO

    private fun compareVersionStrings(left: String, right: String): Int {
        val leftParts = left.split('.').map { it.toBigIntegerOrNull() ?: BigInteger.ZERO }
        val rightParts = right.split('.').map { it.toBigIntegerOrNull() ?: BigInteger.ZERO }
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val compared = leftParts.getOrElse(index) { BigInteger.ZERO }
                .compareTo(rightParts.getOrElse(index) { BigInteger.ZERO })
            if (compared != 0) return compared
        }
        return 0
    }

    private fun nullableString(root: JSONObject, key: String): String? =
        if (root.isNull(key)) null else root.getString(key).takeIf { it.isNotBlank() }

    private fun inspectionDiagnostic(root: JSONObject): String = buildList {
        nullableString(root, "diagnostic")?.let { add(it.take(MAX_DIAGNOSTIC_DETAIL_CHARS)) }
        root.optJSONArray("warnings")?.let { warnings ->
            for (index in 0 until minOf(warnings.length(), MAX_PATCH_FILES)) {
                if (!warnings.isNull(index)) {
                    warnings.optString(index).takeIf { it.isNotBlank() }
                        ?.let { add(it.take(MAX_DIAGNOSTIC_DETAIL_CHARS)) }
                }
            }
        }
        if (root.optBoolean("recoveryPending", false)) {
            add("중단된 작업이 남아 있습니다. 다음 작업 시작 전에 복구가 필요합니다.")
        }
    }.distinct().joinToString("\n").take(MAX_DIAGNOSTIC_DETAIL_CHARS)

    private fun parseStrings(root: JSONObject, key: String): List<String> {
        if (root.isNull(key)) return emptyList()
        val values = root.getJSONArray(key)
        require(values.length() <= MAX_PATCH_FILES) { "$key 항목이 너무 많습니다." }
        return List(values.length()) { index ->
            limitedText(values.getString(index), MAX_DIAGNOSTIC_DETAIL_CHARS, key)
        }
    }

    fun safeRelativePath(value: String): String {
        val normalized = value.replace('\\', '/')
        require(
            normalized.isNotBlank() &&
                normalized.length <= MAX_RELATIVE_PATH_CHARS &&
                !normalized.startsWith('/')
        ) {
            "안전하지 않은 Addressables 경로입니다."
        }
        require(normalized.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
            "안전하지 않은 Addressables 경로입니다."
        }
        return normalized
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun requireHex(value: String, length: Int, label: String): String {
        val normalized = value.lowercase(Locale.ROOT)
        require(normalized.length == length && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            "$label 값이 올바르지 않습니다."
        }
        return normalized
    }

    private fun positive(value: Long, label: String): Long {
        require(value > 0) { "$label 값이 올바르지 않습니다." }
        return value
    }

    private fun limitedText(value: String, maxLength: Int, label: String): String {
        require(value.length <= maxLength) { "$label 길이가 허용 범위를 초과합니다." }
        return value
    }

    private const val MAX_PATCH_FILES = 2_048
    private const val MAX_RELATIVE_PATH_CHARS = 1_024
    private const val MAX_INSPECTION_REQUEST_BYTES = 256 * 1_024
    private const val MAX_DIAGNOSTIC_EVENTS = 512
    private const val MAX_DIAGNOSTIC_DETAIL_CHARS = 4_096
    private val TARGET_STATES = setOf(
        "ORIGINAL", "CURRENT_PATCH", "KNOWN_OLDER_PATCH", "UNKNOWN_MODIFIED",
        "MISSING", "UNREADABLE", "AMBIGUOUS",
    )
}

object TrustedUrls {
    private const val PATCH_REPOSITORY_PATH = "/maynut02/astral-party-korean-patch/"
    private const val AUTO_PATCHER_REPOSITORY_PATH = "/maynut02/astral-party-auto-patcher/"
    private const val SHIZUKU_RELEASE_PATH = "/thedjchi/Shizuku/releases/download/"

    fun requireIndex(value: String) {
        require(value == RELEASE_INDEX_URL) { "신뢰하지 않는 release index 주소입니다." }
    }

    fun requireReleaseAsset(value: String) {
        val uri = parseHttps(value)
        require(uri.host.equals("github.com", ignoreCase = true)) { "신뢰하지 않는 patch host입니다." }
        require(uri.path.startsWith("${PATCH_REPOSITORY_PATH}releases/download/")) {
            "신뢰하지 않는 patch 경로입니다."
        }
        require(uri.rawQuery == null && uri.rawFragment == null) { "patch URL에 query나 fragment를 사용할 수 없습니다." }
    }

    fun requireAutoPatcherReleaseAsset(value: String) {
        val uri = parseHttps(value)
        require(uri.host.equals("github.com", ignoreCase = true)) {
            "신뢰하지 않는 auto patcher host입니다."
        }
        require(uri.path.startsWith("${AUTO_PATCHER_REPOSITORY_PATH}releases/download/")) {
            "신뢰하지 않는 auto patcher 경로입니다."
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "auto patcher URL에 query나 fragment를 사용할 수 없습니다."
        }
    }

    fun requireShizukuApi(value: String) {
        require(value == SHIZUKU_RELEASE_API) { "신뢰하지 않는 Shizuku API 주소입니다." }
    }

    fun requireMobilePatcherIndex(value: String) {
        require(value == MOBILE_PATCHER_INDEX_URL) {
            "신뢰하지 않는 AndroidPatcher index 주소입니다."
        }
    }

    fun requireOriginalGameIndex(value: String) {
        require(value == ORIGINAL_GAME_INDEX_URL) {
            "신뢰하지 않는 원본 게임 APK index 주소입니다."
        }
    }

    fun requireShizukuReleaseAsset(value: String) {
        val uri = parseHttps(value)
        require(uri.host.equals("github.com", ignoreCase = true)) {
            "신뢰하지 않는 Shizuku 다운로드 host입니다."
        }
        require(uri.path.startsWith(SHIZUKU_RELEASE_PATH)) {
            "신뢰하지 않는 Shizuku 다운로드 경로입니다."
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "Shizuku 다운로드 URL에 query나 fragment를 사용할 수 없습니다."
        }
    }

    fun isTrustedRedirect(value: String): Boolean {
        val uri = runCatching { parseHttps(value) }.getOrNull() ?: return false
        val host = uri.host.lowercase(Locale.ROOT)
        return host == "github.com" ||
            host == "api.github.com" ||
            host == "release-assets.githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    private fun parseHttps(value: String): URI {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "HTTPS URL만 사용할 수 있습니다."
        }
        return uri
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
