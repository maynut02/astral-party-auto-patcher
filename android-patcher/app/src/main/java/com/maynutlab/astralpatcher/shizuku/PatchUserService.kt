package com.maynutlab.astralpatcher.shizuku

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import com.maynutlab.astralpatcher.BuildConfig
import com.maynutlab.astralpatcher.IPatchService
import com.maynutlab.astralpatcher.core.GameTarget
import com.maynutlab.astralpatcher.core.PatchProtocol
import com.maynutlab.astralpatcher.core.requireSupportedGamePackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

@Keep
class PatchUserService : IPatchService.Stub() {
    private data class TargetStorage(
        val packageName: String,
        val filesRoot: File,
        val bundleRoot: File,
        val transactionsRoot: File,
        val stateFile: File,
        val diagnosticsFile: File,
    )

    private fun storageFor(packageName: String): TargetStorage {
        requirePrimaryUser()
        val safePackage = requireSupportedGamePackage(packageName)
        val filesRoot = File("/storage/emulated/0/Android/data/$safePackage/files")
        val addressablesRoot = File(filesRoot, "com.unity.addressables")
        val stateRoot = File(filesRoot, ".astralpatch-manager")
        return TargetStorage(
            packageName = safePackage,
            filesRoot = filesRoot,
            bundleRoot = File(addressablesRoot, "AssetBundles"),
            transactionsRoot = File(stateRoot, "transactions"),
            stateFile = File(stateRoot, "state.json"),
            diagnosticsFile = File(stateRoot, "patch-diagnostics.jsonl"),
        )
    }

    private val gameInstallRoot = File("/data/local/tmp/astral-original-installer")
    private var activeGameInstallId: String? = null
    private var expectedGameApkCount = 0
    private var gameInstallStatus = "idle"

    private val activePatchPackages = mutableSetOf<String>()
    private val patchDiagnosticIds = mutableMapOf<String, String>()

    override fun getServiceInfo(): String =
        "AstralPatchService/8 uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} " +
            "userId=${android.os.Process.myUid() / 100000} supportedUserId=0 " +
            "buildVersion=${BuildConfig.VERSION_NAME} buildCode=${BuildConfig.VERSION_CODE} installer=$gameInstallStatus"

    private fun requirePrimaryUser() {
        require(android.os.Process.myUid() / 100000 == 0) { "보조 사용자 프로필은 지원하지 않습니다. 기본 사용자에서 실행해 주세요." }
    }

    @Synchronized
    override fun beginGameInstall(installId: String, fileCount: Int) {
        requirePrimaryUser()
        val safeId = requireTransactionId(installId)
        require(activeGameInstallId == null) { "다른 게임 설치가 이미 진행 중입니다." }
        require(fileCount in 1..MAX_GAME_APK_FILES) { "설치할 게임 APK 파일 수가 올바르지 않습니다." }
        cleanupGameInstallRoot()
        require(gameInstallRoot.isDirectory || gameInstallRoot.mkdirs()) {
            "게임 APK staging root를 만들 수 없습니다."
        }
        val staging = gameInstallDirectory(safeId)
        require(staging.mkdir()) { "게임 APK staging 디렉터리를 만들 수 없습니다." }
        activeGameInstallId = safeId
        expectedGameApkCount = fileCount
        gameInstallStatus = "staging 0/$fileCount"
    }

    @Synchronized
    override fun stageGameApk(
        installId: String,
        name: String,
        apk: ParcelFileDescriptor,
        size: Long,
        sha256: String,
    ) {
        val safeId = requireActiveGameInstall(installId)
        require(name.matches(SAFE_APK_NAME)) { "게임 APK 파일 이름이 안전하지 않습니다." }
        val expectedSize = requirePositive(size, "게임 APK 크기")
        val expectedSha = requireSha256(sha256)
        val staging = gameInstallDirectory(safeId)
        val target = File(staging, name)
        require(target.parentFile == staging && !target.exists()) {
            "게임 APK staging 경로가 올바르지 않거나 중복되었습니다."
        }
        receiveGameApk(apk, target, expectedSize, expectedSha)
        Os.chmod(target.path, 0b110100100)
        val stagedCount = stagedGameApks(staging).size
        require(stagedCount <= expectedGameApkCount) { "예상보다 많은 게임 APK가 전달되었습니다." }
        gameInstallStatus = "staging $stagedCount/$expectedGameApkCount"
    }

    @Synchronized
    override fun commitGameInstall(installId: String): String {
        val safeId = requireActiveGameInstall(installId)
        val staging = gameInstallDirectory(safeId)
        val apks = stagedGameApks(staging)
        require(apks.size == expectedGameApkCount) {
            "게임 APK 파일 수가 다릅니다: ${apks.size}/$expectedGameApkCount"
        }
        require(apks.any { it.name == "base.apk" }) { "base APK가 staging에 없습니다." }
        gameInstallStatus = "installing ${apks.size} files"
        var packageSessionId: Int? = null
        return try {
            val totalSize = apks.sumOf(File::length)
            val createResult = runInstallCommand(
                listOf(
                    "/system/bin/pm",
                    "install-create",
                    "-S",
                    totalSize.toString(),
                    "-r",
                    "-i",
                    PLAY_STORE_PACKAGE,
                )
            )
            val sessionId = INSTALL_SESSION_ID.find(createResult)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: error("package install session ID를 확인할 수 없습니다: $createResult")
            packageSessionId = sessionId
            apks.forEachIndexed { index, apk ->
                gameInstallStatus = "writing ${index + 1}/${apks.size} session=$sessionId"
                runInstallCommand(
                    listOf(
                        "/system/bin/pm",
                        "install-write",
                        "-S",
                        apk.length().toString(),
                        sessionId.toString(),
                        apk.name,
                        apk.absolutePath,
                    )
                )
            }
            gameInstallStatus = "committing session=$sessionId"
            val result = runInstallCommand(
                listOf(
                    "/system/bin/pm",
                    "install-commit",
                    sessionId.toString(),
                )
            )
            packageSessionId = null
            gameInstallStatus = "success ${apks.size} files"
            result
        } catch (error: Exception) {
            packageSessionId?.let { sessionId ->
                runCatching {
                    runInstallCommand(
                        listOf(
                            "/system/bin/pm",
                            "install-abandon",
                            sessionId.toString(),
                        )
                    )
                }
            }
            gameInstallStatus = "failed ${error.message.orEmpty().take(MAX_STATUS_OUTPUT_CHARS)}"
            throw error
        } finally {
            finishGameInstall(staging)
        }
    }

    @Synchronized
    override fun cancelGameInstall(installId: String) {
        val safeId = requireTransactionId(installId)
        if (activeGameInstallId != safeId) return
        finishGameInstall(gameInstallDirectory(safeId))
        gameInstallStatus = "cancelled"
    }

    @Synchronized
    override fun inspectGame(packageName: String): String = with(storageFor(packageName)) {
        val warnings = mutableListOf<String>()
        val paths = runCatching { installedApkPaths(packageName) }.getOrElse {
            warnings.add("설치 APK 경로를 확인하지 못했습니다: ${it.message}")
            emptyList()
        }
        val catalog = runCatching { GameResourceLocator(filesRoot, paths).inspectCatalogs() }.getOrElse {
            warnings.add("catalog 검사 실패: ${it.message}")
            null
        }
        warnings.addAll(relevantCatalogWarnings(catalog))
        val pending = pendingRecovery()
        if (pending.optBoolean("pending")) warnings.add("중단된 작업이 있습니다. 다음 실행 시작 시 복구합니다.")
        val history = inspectPatchHistory(stateFile, catalog?.selectedHash)
        JSONObject().put("schemaVersion", 2)
            .put("gameVersion", catalog?.selectedVersion.orEmpty())
            .put("catalogHash", catalog?.selectedHash.orEmpty())
            .put("patchVersion", history.optString("patchVersion"))
            .put("history", history).put("historyStatus", history.getString("status"))
            .put("historyError", history.get("error"))
            .put("recoveryPending", pending.optBoolean("pending")).put("pendingRecovery", pending)
            .put("warnings", JSONArray(warnings.distinct()))
            .toString()
    }

    @Synchronized
    override fun inspectPatchTargets(packageName: String, requirementsJson: String): String = with(storageFor(packageName)) {
        require(requirementsJson.toByteArray(Charsets.UTF_8).size <= MAX_INSPECTION_REQUEST_BYTES) { "리소스 검사 요청이 너무 큽니다." }
        val request = JSONObject(requirementsJson)
        validateReleaseRequest(packageName, request)
        val locator = GameResourceLocator(filesRoot, apkPaths(snapshotString(request)?.let(::JSONObject)))
        val warnings = mutableListOf<String>()
        val catalog = runCatching { locator.inspectCatalogs() }.getOrElse {
            warnings.add("catalog 검사 실패: ${it.message}")
            null
        }
        warnings.addAll(relevantCatalogWarnings(catalog))
        val evidence = patchCatalogEvidence(catalog, request.getJSONArray("files"))
        warnings.addAll(evidence.second)
        val pending = pendingRecovery()
        if (pending.optBoolean("pending")) warnings.add("중단된 작업이 있습니다. 다음 실행 시작 시 복구 후 재검사가 필요할 수 있습니다.")
        inspectPlannedTargets(filesRoot, packageName, request, catalog?.selectedHash, warnings, evidence.first) { path ->
            val candidates = locator.findTargets(path)
            candidates.files to candidates.errors
        }.put("recoveryPending", pending.optBoolean("pending")).put("pendingRecovery", pending).toString()
    }

    @Synchronized
    override fun beginPlannedPatch(transactionId: String, packageName: String, planJson: String) = with(storageFor(packageName)) {
        val safeId = requireTransactionId(transactionId)
        val plan = PlannedPatch.parse(packageName, planJson)
        require(packageName !in activePatchPackages) { "같은 게임의 다른 작업이 진행 중입니다." }
        patchDiagnosticIds[packageName] = safeId
        resetDiagnostics(safeId, "package=$packageName operation=${plan.operation} release=${plan.catalogHash} observed=${plan.observedCatalogHash}")
        diagnosticOperation(safeId, "begin_planned_patch") {
            validatePlanEnvironment(plan, verifyMapping = true)
            transactions().validateObserved(plan)
            forceStopGame(packageName)
            // Recovery is a mutating, explicit begin step. Read-only inspections never enter here.
            recoverInterruptedTransactions()
            validatePlanEnvironment(plan, verifyMapping = true)
            transactions().validateObserved(plan)
            transactions().begin(safeId, plan)
            activePatchPackages.add(packageName)
            Unit
        }
    }

    @Synchronized
    override fun beginPatch(transactionId: String, packageName: String, catalogHash: String) {
        error("계획 없는 설치는 지원하지 않습니다. 리소스를 다시 검사하고 beginPlannedPatch를 사용해 주세요.")
    }

    @Synchronized
    override fun stageOriginal(
        transactionId: String,
        packageName: String,
        original: ParcelFileDescriptor,
        sourceSize: Long,
        sourceSha256: String,
        relativePath: String,
    ) {
        // Retain Binder transaction 9 so older clients cannot accidentally call another API.
        // Originals now enter applyFile only for RESTORE; PATCH needs only its patch payload.
        original.use { error("영구 원본 보관은 지원하지 않습니다. 최신 패처에서 다시 시도해 주세요.") }
    }

    @Synchronized
    override fun applyFile(
        transactionId: String,
        packageName: String,
        payload: ParcelFileDescriptor,
        size: Long,
        sha256: String,
        sourceSize: Long,
        sourceSha256: String,
        relativePath: String,
    ): String = try {
        ParcelFileDescriptor.AutoCloseInputStream(payload).use { input ->
            with(storageFor(packageName)) {
                val safeId = requireTransactionId(transactionId)
                diagnosticOperation(safeId, "apply_file", "path=$relativePath") {
                    val engine = transactions()
                    validatePlanEnvironment(engine.plan(safeId))
                    engine.apply(safeId, relativePath, input, size, sha256, sourceSize, sourceSha256)
                }
            }
        }
        PatchProtocol.createApplyFileResult()
    } catch (error: Exception) {
        PatchProtocol.createApplyFileResult(error)
    }

    @Synchronized
    override fun commitPatch(
        transactionId: String,
        packageName: String,
        gameVersion: String,
        catalogHash: String,
        patchVersion: String,
    ) = with(storageFor(packageName)) {
        val safeId = requireTransactionId(transactionId)
        diagnosticOperation(safeId, "commit_patch") {
            val engine = transactions()
            validatePlanEnvironment(engine.plan(safeId))
            engine.commit(safeId, gameVersion, catalogHash, patchVersion)
            activePatchPackages.remove(packageName)
            Unit
        }
    }

    @Synchronized
    override fun rollbackPatch(transactionId: String, packageName: String) = with(storageFor(packageName)) {
        val safeId = requireTransactionId(transactionId)
        diagnosticOperation(safeId, "rollback_patch") {
            val transaction = transactionRoot(safeId)
            if (File(transaction, "catalog.txt").isFile && !File(transaction, "plan.json").exists()) {
                requireTransactionPackage(transaction, packageName)
                rollbackTransaction(transaction)
            } else transactions().rollback(safeId)
            activePatchPackages.remove(packageName)
            Unit
        }
    }

    private fun TargetStorage.transactions() = PatchTransaction(
        filesRoot, packageName,
        overwrite = { source, target -> overwriteExistingFile(source, target) },
        syncDirectory = { directory ->
            try {
                require(directory.isDirectory) { "fsync 대상이 디렉터리가 아닙니다." }
                val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
            } catch (error: ErrnoException) {
                // Some emulated-storage/FUSE implementations cannot sync directories.
                // Continue only for explicit unsupported-operation errors, retaining diagnostics.
                if (error.errno != OsConstants.EINVAL && error.errno != OsConstants.ENOSYS && error.errno != OsConstants.EOPNOTSUPP) throw error
                patchDiagnosticIds[packageName]?.let { id -> appendDiagnostic(id, "directory_fsync_unsupported", "errno=${error.errno} path=${directory.path}") }
            }
        },
    )

    private fun TargetStorage.validatePlanEnvironment(plan: PlannedPatch, verifyMapping: Boolean = false) {
        require(plan.packageName == packageName)
        val paths = apkPaths(plan.apkSnapshot?.let(::JSONObject))
        if (paths.isNotEmpty()) {
            val current = installedApkPaths(packageName).map { File(it).canonicalPath }.toSet()
            require(current == paths.map { File(it).canonicalPath }.toSet()) { "검사 후 설치 APK가 변경되었습니다. 다시 검사해 주세요." }
        }
        val locator = GameResourceLocator(filesRoot, paths)
        val observed = locator.inspectCatalogs().selectedHash
        require(observed == plan.observedCatalogHash) { "검사 후 로컬 catalog가 변경되었습니다. 다시 검사해 주세요." }
        if (verifyMapping) plan.targets.forEach { target ->
            val candidates = locator.findTargets(target.path)
            require(candidates.errors.isEmpty() && candidates.files.size == 1 &&
                candidates.files.single().canonicalFile == patchChild(filesRoot, target.actualPath)) {
                "검사 후 대상 경로가 변경되었거나 중복되었습니다: ${target.path}"
            }
        }
    }

    private fun relevantCatalogWarnings(catalog: CatalogObservation?): List<String> {
        if (catalog == null) return emptyList()
        val irrelevant = catalog.candidates.filter { candidate ->
            catalog.selectedVersion != null && candidate.version != catalog.selectedVersion
        }.flatMap { it.warnings }.toSet()
        return catalog.warnings.filterNot { it in irrelevant }
    }

    private fun TargetStorage.pendingRecovery(): JSONObject {
        val result = JSONObject().put("pending", false).put("count", 0).put("error", JSONObject.NULL)
        try {
            if (transactionsRoot.exists()) {
                val entries = requireNotNull(transactionsRoot.listFiles()) { "이전 작업 목록을 읽을 수 없습니다." }
                result.put("pending", entries.isNotEmpty()).put("count", entries.size)
            }
        } catch (error: Exception) {
            result.put("pending", true).put("error", error.message ?: error.javaClass.simpleName)
        }
        return result
    }

    private fun installedApkPaths(packageName: String): List<String> {
        requireSupportedGamePackage(packageName)
        val output = runInstallCommand(listOf("/system/bin/pm", "path", "--user", "0", packageName), MAX_INSTALL_OUTPUT_BYTES)
        val paths = output.lineSequence().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toList()
        require(paths.isNotEmpty() && paths.all { it.startsWith('/') && it.endsWith(".apk") }) { "기본 사용자의 설치 APK 경로를 확인할 수 없습니다." }
        return paths.distinct()
    }

    @Synchronized
    override fun getPatchDiagnostics(transactionId: String, packageName: String): String =
        with(storageFor(packageName)) {
            val safeId = requireTransactionId(transactionId)
            val transaction = transactionRoot(safeId)
            val events = JSONArray()
            var readError = ""
            if (diagnosticsFile.isFile) {
                runCatching {
                    diagnosticsFile.useLines { lines ->
                        lines.take(MAX_DIAGNOSTIC_EVENTS).forEach { line ->
                            if (line.isNotBlank()) {
                                val event = JSONObject(line)
                                if (event.optString("transactionId") == safeId) events.put(event)
                            }
                        }
                    }
                }.onFailure { error -> readError = error.message ?: error.javaClass.simpleName }
            }
            JSONObject()
                .put("schemaVersion", 1)
                .put("serviceInfo", getServiceInfo())
                .put("transactionId", safeId)
                .put("events", events)
                .put("transaction", transactionSnapshot(transaction))
                .put("diagnosticsFileExists", diagnosticsFile.isFile)
                .put("diagnosticsFileBytes", diagnosticsFile.takeIf(File::isFile)?.length() ?: 0L)
                .put("readError", readError)
                .toString()
        }

    @Synchronized
    override fun restorePatch(packageName: String): String {
        requireSupportedGamePackage(packageName)
        error("계획 없는 복구는 지원하지 않습니다. 원본 릴리스를 선택하고 RESTORE 계획을 실행해 주세요.")
    }

    private fun forceStopGame(packageName: String) {
        require(packageName in GameTarget.supportedPackages) { "지원하지 않는 게임 패키지입니다." }
        val process = ProcessBuilder("/system/bin/am", "force-stop", "--user", "0", packageName)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().take(1024) }
        val exit = process.waitFor()
        require(exit == 0) { "게임 종료에 실패했습니다: $output" }
    }

    override fun destroy() {
        activeGameInstallId?.let { finishGameInstall(gameInstallDirectory(it)) }
        System.exit(0)
    }

    private fun receiveGameApk(
        descriptor: ParcelFileDescriptor,
        target: File,
        expectedSize: Long,
        expectedSha: String,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= expectedSize) { "게임 APK stream이 예상 크기를 초과했습니다." }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
        require(total == expectedSize) { "게임 APK stream 크기가 다릅니다." }
        require(digest.digest().toHex() == expectedSha) { "게임 APK stream SHA-256이 다릅니다." }
        verifyFile(target, expectedSize, expectedSha, "staging된 게임 APK")
    }

    private fun runInstallCommand(command: List<String>, outputLimit: Int = MAX_STATUS_OUTPUT_CHARS): String {
        require(outputLimit in 1..MAX_INSTALL_OUTPUT_BYTES)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val captured = ByteArrayOutputStream()
        val reader = Thread(
            {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val remaining = MAX_INSTALL_OUTPUT_BYTES - captured.size()
                        if (remaining > 0) captured.write(buffer, 0, minOf(read, remaining))
                    }
                }
            },
            "pm-install-output",
        ).apply { start() }
        try {
            if (!process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("게임 APK 설치 시간이 초과되었습니다.")
            }
            reader.join(OUTPUT_READER_JOIN_TIMEOUT_MS)
            val output = captured.toString(Charsets.UTF_8.name()).trim()
            val operation = command.getOrNull(1) ?: "install"
            require(process.exitValue() == 0) {
                "pm $operation 실패(exit=${process.exitValue()}): " +
                    output.take(MAX_STATUS_OUTPUT_CHARS)
            }
            return output.ifBlank { "Success" }.take(outputLimit)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            if (reader.isAlive) reader.join(OUTPUT_READER_JOIN_TIMEOUT_MS)
        }
    }

    private fun requireActiveGameInstall(installId: String): String {
        val safeId = requireTransactionId(installId)
        require(activeGameInstallId == safeId) { "활성 게임 설치 session이 아닙니다." }
        return safeId
    }

    private fun gameInstallDirectory(installId: String): File {
        val target = File(gameInstallRoot, requireTransactionId(installId))
        require(target.parentFile == gameInstallRoot) { "게임 설치 경로가 안전하지 않습니다." }
        return target
    }

    private fun stagedGameApks(staging: File): List<File> =
        staging.listFiles()
            ?.filter { it.isFile && it.name.matches(SAFE_APK_NAME) }
            ?.sortedWith(compareBy<File> { it.name != "base.apk" }.thenBy(File::getName))
            .orEmpty()

    private fun finishGameInstall(staging: File) {
        deleteRecursively(staging)
        activeGameInstallId = null
        expectedGameApkCount = 0
    }

    private fun cleanupGameInstallRoot() {
        gameInstallRoot.listFiles()?.forEach(::deleteRecursively)
    }

    private fun TargetStorage.recoverInterruptedTransactions() {
        transactionsRoot.listFiles()?.filter { it.isDirectory && File(it, "catalog.txt").isFile && !File(it, "plan.json").exists() }
            ?.forEach { requireTransactionPackage(it, packageName); rollbackTransaction(it) }
        transactions().recoverAll()
    }

    private fun TargetStorage.rollbackTransaction(transaction: File) {
        // Compatibility recovery for service v6 journals; never run by inspections.
        val paths = readJournal(File(transaction, "journal.txt"))
        paths.forEach { relative ->
            require(safePath(transaction, "previous/$relative").isFile && safePath(bundleRoot, relative).isFile) {
                "이전 서비스의 롤백 사본 또는 대상이 없습니다: $relative"
            }
        }
        paths.asReversed().forEach { relative -> replaceFile(safePath(transaction, "previous/$relative"), safePath(bundleRoot, relative)) }
        deleteRecursively(transaction)
        require(!transaction.exists()) { "이전 작업을 정리하지 못했습니다." }
    }

    private fun requireTransactionPackage(transaction: File, packageName: String) {
        val packageFile = File(transaction, "package.txt")
        require(packageFile.isFile && packageFile.readText().trim() == packageName) {
            "patch transaction 대상 게임이 다릅니다."
        }
    }

    private fun TargetStorage.transactionRoot(transactionId: String): File = safePath(transactionsRoot, transactionId)

    private fun TargetStorage.replaceFile(
        source: File,
        target: File,
    ) {
        require(source.isFile) { "교체할 원본 파일을 찾을 수 없습니다: $source" }
        require(target.isFile) { "교체할 게임 리소스를 찾을 수 없습니다: $target" }
        val expectedSize = source.length()
        val expectedSha = sha256(source)

        // Android/data 아래의 게임 소유 디렉터리는 shell UID가 기존 파일을 읽고
        // 덮어쓸 수 있어도 새 sibling 파일 생성은 거부할 수 있다. 대상 파일을
        // 삭제/rename하지 않고 기존 inode를 그대로 유지한 채 내용을 교체한다.
        overwriteExistingFile(source, target)
        verifyFile(target, expectedSize, expectedSha, "교체된 파일")
    }

    private fun TargetStorage.overwriteExistingFile(
        source: File,
        target: File,
    ) {
        require(target.isFile) { "덮어쓸 게임 리소스를 찾을 수 없습니다: $target" }
        BufferedInputStream(FileInputStream(source)).use { input ->
            // O_CREAT를 사용하지 않아 Android/data 디렉터리의 새 파일 생성 제한을 피한다.
            val descriptor = Os.open(
                target.path,
                OsConstants.O_WRONLY or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW,
                0,
            )
            FileOutputStream(descriptor).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput)
                input.copyTo(output, BUFFER_SIZE)
                output.flush()
                fileOutput.fd.sync()
            }
        }
    }

    private fun verifyFile(file: File, expectedSize: Long, expectedSha: String, label: String) {
        require(file.isFile && file.length() == expectedSize && sha256(file) == expectedSha) {
            "$label 검증에 실패했습니다: $file"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun readJournal(journal: File): List<String> =
        if (!journal.isFile) emptyList()
        else journal.readLines().filter { it.isNotBlank() }.map(PatchProtocol::safeRelativePath)

    private inline fun <T> TargetStorage.diagnosticOperation(
        transactionId: String,
        stage: String,
        detail: String = "",
        block: () -> T,
    ): T {
        appendDiagnostic(transactionId, "${stage}_enter", detail)
        return try {
            block().also { appendDiagnostic(transactionId, "${stage}_complete", transactionDetail(transactionRoot(transactionId))) }
        } catch (error: Throwable) {
            val message = buildString {
                append("error=")
                append(error.javaClass.name)
                append(" message=")
                append(error.message.orEmpty())
                append(' ')
                append(transactionDetail(transactionRoot(transactionId)))
            }
            appendDiagnostic(transactionId, "${stage}_failed", message)
            throw error
        }
    }

    private fun TargetStorage.resetDiagnostics(transactionId: String, detail: String) {
        runCatching {
            ensureParent(diagnosticsFile)
            FileOutputStream(diagnosticsFile, false).use { output -> output.fd.sync() }
        }
        appendDiagnostic(transactionId, "diagnostics_started", detail)
    }

    private fun TargetStorage.appendDiagnostic(transactionId: String, stage: String, detail: String) {
        runCatching {
            ensureParent(diagnosticsFile)
            if (diagnosticsFile.isFile && diagnosticsFile.length() >= MAX_DIAGNOSTIC_FILE_BYTES) return@runCatching
            val event = JSONObject()
                .put("timestampMs", System.currentTimeMillis())
                .put("elapsedRealtimeNanos", android.os.SystemClock.elapsedRealtimeNanos())
                .put("pid", android.os.Process.myPid())
                .put("uid", android.os.Process.myUid())
                .put("thread", Thread.currentThread().name.take(128))
                .put("transactionId", transactionId)
                .put("stage", stage.take(128))
                .put("detail", detail.take(MAX_DIAGNOSTIC_DETAIL_CHARS))
            FileOutputStream(diagnosticsFile, true).use { output ->
                output.write((event.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        }
    }

    private fun transactionSnapshot(transaction: File): JSONObject {
        val planned = File(transaction, "plan.json").isFile
        val journal = File(transaction, if (planned) "journal.json" else "journal.txt")
        val catalog = File(transaction, if (planned) "plan.json" else "catalog.txt")
        var journalEntries = -1
        var journalReadError = ""
        runCatching {
            journalEntries = if (planned && journal.isFile) JSONObject(journal.readText()).getJSONArray("attempted").length()
                else readJournal(journal).size
        }
            .onFailure { error -> journalReadError = error.message ?: error.javaClass.simpleName }
        return JSONObject()
            .put("exists", transaction.isDirectory)
            .put("path", transaction.path)
            .put("catalogExists", catalog.isFile)
            .put("catalogBytes", if (catalog.isFile) catalog.length() else 0L)
            .put("journalExists", journal.isFile)
            .put("journalBytes", if (journal.isFile) journal.length() else 0L)
            .put("journalEntries", journalEntries)
            .put("journalReadError", journalReadError)
            .put("previousFiles", countFiles(File(transaction, "previous")))
            .put("stagingFiles", if (planned) transaction.listFiles().orEmpty().count { it.isFile && it.name.startsWith("payload-") }
                else countFiles(File(transaction, "staging")))
            .put("planExists", planned).put("ready", File(transaction, "READY").isFile)
            .put("committed", File(transaction, "COMMITTED").isFile)
    }

    private fun transactionDetail(transaction: File): String =
        runCatching { transactionSnapshot(transaction).toString() }
            .getOrElse { error ->
                JSONObject()
                    .put("snapshotError", (error.message ?: error.javaClass.simpleName).take(512))
                    .toString()
            }

    private fun countFiles(root: File): Int =
        if (!root.isDirectory) 0 else root.walkTopDown().count { it.isFile }

    private fun safePath(root: File, relativePath: String): File = patchChild(root, relativePath)

    private fun ensureParent(file: File) {
        val parent = file.parentFile ?: return
        require(parent.isDirectory || parent.mkdirs()) { "디렉터리를 만들 수 없습니다: $parent" }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }

    private fun requireTransactionId(value: String): String {
        require(value.matches(Regex("^[0-9a-f-]{36}$"))) { "transaction ID가 올바르지 않습니다." }
        return value
    }

    private fun requireSha256(value: String): String = requireHex(value, 64, "SHA-256")

    private fun requirePositive(value: Long, label: String): Long {
        require(value > 0) { "$label 값이 올바르지 않습니다." }
        return value
    }

    private fun requireHex(value: String, length: Int, label: String): String {
        val normalized = value.lowercase(Locale.ROOT)
        require(normalized.length == length && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            "$label 값이 올바르지 않습니다."
        }
        return normalized
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val MAX_INSPECTION_REQUEST_BYTES = 256 * 1024
        private const val MAX_DIAGNOSTIC_EVENTS = 512
        private const val MAX_DIAGNOSTIC_DETAIL_CHARS = 4_096
        private const val MAX_DIAGNOSTIC_FILE_BYTES = 512 * 1024L
        private const val MAX_GAME_APK_FILES = 64
        private const val MAX_INSTALL_OUTPUT_BYTES = 64 * 1024
        private const val MAX_STATUS_OUTPUT_CHARS = 1_024
        private const val INSTALL_TIMEOUT_SECONDS = 300L
        private const val OUTPUT_READER_JOIN_TIMEOUT_MS = 5_000L
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private val SAFE_APK_NAME = Regex("^[A-Za-z0-9._-]+\\.apk$")
        private val INSTALL_SESSION_ID = Regex("\\[([0-9]+)]")
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
