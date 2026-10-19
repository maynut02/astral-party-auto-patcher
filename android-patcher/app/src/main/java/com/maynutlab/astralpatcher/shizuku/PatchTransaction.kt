package com.maynutlab.astralpatcher.shizuku

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** Filesystem transaction shared by patch and restore. No Android or Binder dependencies.
 *
 * READY is written only after *all* rollback copies and the prior history are durable.
 * Each attempted target is journaled before truncation. COMMITTED is durable before history
 * publication: recovery must finish publication for that transaction, never undo its files.
 * Release originals are restore payloads supplied by the client, never permanent backups.
 * The only saved game bytes are the current transaction's temporary rollback copies.
 * The service supplies inode-preserving writes and directory fsync for Android/data.
 */
internal class PatchTransaction(
    private val filesRoot: File,
    private val packageName: String,
    private val overwrite: (File, File) -> Unit,
    private val syncDirectory: (File) -> Unit,
) {
    private val stateRoot get() = patchChild(filesRoot, ".astralpatch-manager")
    private val transactionsRoot get() = patchChild(stateRoot, "transactions")
    private val stateFile get() = patchChild(stateRoot, "state.json")

    fun directory(id: String): File = patchChild(transactionsRoot, safeId(id))

    fun begin(id: String, plan: PlannedPatch) {
        require(plan.packageName == packageName)
        validateObserved(plan)
        mkdir(transactionsRoot)
        require(transactionsRoot.listFiles()?.isEmpty() == true) { "이전 작업을 먼저 복구해야 합니다." }
        val transaction = directory(id)
        require(transaction.mkdir()) { "작업 디렉터리를 만들 수 없습니다." }
        syncDirectory(transactionsRoot)
        atomic(patchChild(transaction, "plan.json"), plan.json().toString().toByteArray())
        val priorPresent = Files.exists(stateFile.toPath(), LinkOption.NOFOLLOW_LINKS)
        val journal = JSONObject().put("schemaVersion", 1).put("packageName", packageName)
            .put("plan", plan.json()).put("priorStatePresent", priorPresent).put("attempted", JSONArray())
        if (priorPresent) {
            require(stateFile.isFile && stateFile.length() <= MAX_HISTORY_BYTES)
            val previousState = patchChild(transaction, "previous-state.bin")
            durableCopy(stateFile, previousState)
            journal.put("priorStateSize", previousState.length()).put("priorStateSha256", patchSha(previousState))
        }
        plan.targets.forEachIndexed { index, target ->
            val file = targetFile(target)
            patchVerify(file, target.observedSize, target.observedSha256)
            val backup = previous(transaction, index)
            durableCopy(file, backup)
            patchVerify(backup, target.observedSize, target.observedSha256)
        }
        validateObserved(plan)
        atomicJson(patchChild(transaction, "journal.json"), journal)
        atomic(patchChild(transaction, "READY"), byteArrayOf(1))
    }

    fun plan(id: String): PlannedPatch = load(directory(id)).second

    fun validateObserved(plan: PlannedPatch) {
        plan.targets.forEach { patchVerify(targetFile(it), it.observedSize, it.observedSha256) }
    }

    fun apply(id: String, path: String, input: InputStream, size: Long, sha: String, sourceSize: Long, sourceSha: String) {
        input.use {
            val (transaction, plan, journal) = load(directory(id))
            val index = plan.targets.indexOfFirst { it.path == patchSafeRelative(path) }
            require(index >= 0) { "계획에 없는 파일입니다." }
            val target = plan.targets[index]
            require(size == target.outputSize(plan.operation) && patchHex(sha, 64) == target.outputSha(plan.operation)) {
                "적용할 파일 정보가 계획과 다릅니다."
            }
            require(sourceSize == target.sourceSize && patchHex(sourceSha, 64) == target.sourceSha256) { "원본 정보가 계획과 다릅니다." }
            val attempted = attempted(journal, plan)
            require(index !in attempted) { "같은 파일을 두 번 적용할 수 없습니다." }
            // Recheck every target: already written targets against the output, untouched ones
            // against the original inspection snapshot. Never rediscover or substitute a path.
            plan.targets.forEachIndexed { i, item ->
                patchVerify(targetFile(item), if (i in attempted) item.outputSize(plan.operation) else item.observedSize,
                    if (i in attempted) item.outputSha(plan.operation) else item.observedSha256)
                patchVerify(previous(transaction, i), item.observedSize, item.observedSha256)
            }
            val staged = patchChild(transaction, "payload-$index")
            receive(it, staged, size, target.outputSha(plan.operation))
            journal.put("attempted", JSONArray(attempted + index))
            atomicJson(patchChild(transaction, "journal.json"), journal)
            // Resolving again catches a replaced parent/symlink immediately before opening.
            val actual = targetFile(target)
            patchVerify(actual, target.observedSize, target.observedSha256)
            overwrite(staged, actual)
            patchVerify(targetFile(target), size, target.outputSha(plan.operation))
        }
    }

    fun commit(id: String, gameVersion: String, catalogHash: String, patchVersion: String, userId: Int = 0) {
        val (transaction, plan, journal) = load(directory(id))
        require(gameVersion == plan.gameVersion && patchHex(catalogHash, 32) == plan.catalogHash && patchVersion == plan.patchVersion) {
            "완료 요청의 릴리스 정보가 계획과 다릅니다."
        }
        require(attempted(journal, plan).toSet() == plan.targets.indices.toSet()) { "계획한 모든 파일을 적용하지 않았습니다." }
        plan.targets.forEach { patchVerify(targetFile(it), it.outputSize(plan.operation), it.outputSha(plan.operation)) }
        val state = plan.json().put("schemaVersion", 2).put("userId", userId)
            .put("releasePatchVersion", plan.patchVersion)
            .put("patchVersion", if (plan.operation == PatchOperation.PATCH) plan.patchVersion else "")
            .put("transactionId", id).put("committedAtMs", System.currentTimeMillis())
            .put("files", JSONArray(plan.targets.map { target ->
                target.json().put("size", target.outputSize(plan.operation)).put("sha256", target.outputSha(plan.operation))
            }))
        val pending = patchChild(transaction, "next-state.json")
        atomicJson(pending, state)
        atomicJson(patchChild(transaction, "COMMITTED"), JSONObject().put("size", pending.length()).put("sha256", patchSha(pending)))
        // COMMITTED is now authoritative, including if publication/cleanup is interrupted.
        publishCommitted(transaction)
        cleanupCompleted(transaction)
    }

    fun rollback(id: String) = recover(directory(id))

    fun recoverAll() {
        if (!transactionsRoot.exists()) return
        val entries = requireNotNull(transactionsRoot.listFiles()) { "이전 작업 목록을 읽을 수 없습니다." }
        entries.forEach { entry ->
            if (entry.name.endsWith(".finished")) {
                safeId(entry.name.removeSuffix(".finished"))
                require(entry == patchChild(transactionsRoot, entry.name))
                remove(entry)
                return@forEach
            }
            require(entry.isDirectory && entry == directory(entry.name)) { "안전하지 않은 작업 디렉터리입니다." }
            recover(entry)
        }
    }

    private fun recover(transaction: File) {
        if (!transaction.exists()) return
        require(patchChild(transaction, "plan.json").exists() ||
            (!patchChild(transaction, "journal.txt").exists() && !patchChild(transaction, "catalog.txt").exists())) {
            "이전 서비스의 작업은 호환 복구 경로로 처리해야 합니다."
        }
        if (patchChild(transaction, "ROLLED_BACK").isFile) {
            cleanupCompleted(transaction)
            return
        }
        if (patchChild(transaction, "COMMITTED").isFile) {
            publishCommitted(transaction)
            cleanupCompleted(transaction)
            return
        }
        if (!patchChild(transaction, "READY").isFile) {
            // No overwrite or history publication is possible before READY.
            remove(transaction)
            return
        }
        val (_, plan, journal) = load(transaction)
        val writes = attempted(journal, plan)
        // Validate ALL required backups and paths before restoring the first byte.
        writes.forEach { index ->
            val target = plan.targets[index]
            require(targetFile(target).isFile) { "롤백 대상이 없어 복구를 완료할 수 없습니다." }
            patchVerify(previous(transaction, index), target.observedSize, target.observedSha256)
        }
        val prior = patchChild(transaction, "previous-state.bin")
        if (journal.getBoolean("priorStatePresent")) {
            patchVerify(prior, journal.getLong("priorStateSize"), patchHex(journal.getString("priorStateSha256"), 64))
        }
        writes.asReversed().forEach { index ->
            val target = plan.targets[index]
            overwrite(previous(transaction, index), targetFile(target))
            patchVerify(targetFile(target), target.observedSize, target.observedSha256)
        }
        if (journal.getBoolean("priorStatePresent")) atomic(stateFile, prior.readBytes())
        else if (stateFile.exists()) { require(stateFile.delete()); syncDirectory(stateRoot) }
        // Prevent a crash during cleanup from leaving a READY transaction without its backups.
        atomic(patchChild(transaction, "ROLLED_BACK"), byteArrayOf(1))
        cleanupCompleted(transaction)
    }

    private fun load(transaction: File): Triple<File, PlannedPatch, JSONObject> {
        require(patchChild(transaction, "READY").isFile && !patchChild(transaction, "COMMITTED").exists() &&
            !patchChild(transaction, "ROLLED_BACK").exists()) { "활성 작업 계획을 찾을 수 없습니다." }
        val journal = readJson(patchChild(transaction, "journal.json"))
        require(journal.getInt("schemaVersion") == 1 && journal.getString("packageName") == packageName)
        val plan = PlannedPatch.parse(packageName, journal.getJSONObject("plan").toString())
        require(PlannedPatch.parse(packageName, readJson(patchChild(transaction, "plan.json")).toString()) == plan)
        return Triple(transaction, plan, journal)
    }

    private fun attempted(journal: JSONObject, plan: PlannedPatch): List<Int> {
        val array = journal.getJSONArray("attempted")
        return (0 until array.length()).map { array.getInt(it).also { index -> require(index in plan.targets.indices) } }
            .also { require(it.size == it.distinct().size) }
    }

    private fun publishCommitted(transaction: File) {
        val marker = readJson(patchChild(transaction, "COMMITTED"))
        val pending = patchChild(transaction, "next-state.json")
        // Publication may already have succeeded before a crash during cleanup.
        if (!pending.isFile) {
            patchVerify(stateFile, marker.getLong("size"), patchHex(marker.getString("sha256"), 64))
            return
        }
        patchVerify(pending, marker.getLong("size"), patchHex(marker.getString("sha256"), 64))
        val state = readJson(pending)
        require(state.getString("packageName") == packageName && state.getInt("schemaVersion") == 2)
        atomic(stateFile, pending.readBytes())
    }

    private fun cleanupCompleted(transaction: File) {
        // Rename first: an interrupted recursive cleanup cannot turn a committed transaction
        // into an uncommitted one. Recovery treats this sibling tombstone as cleanup only.
        val garbage = patchChild(transactionsRoot, "${transaction.name}.finished")
        Files.move(transaction.toPath(), garbage.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(transactionsRoot)
        remove(garbage)
    }

    private fun targetFile(target: PlannedTarget) = patchChild(filesRoot, target.actualPath)
    private fun previous(transaction: File, index: Int) = patchChild(transaction, "previous/$index")
    private fun receive(input: InputStream, target: File, size: Long, sha: String) {
        mkdir(requireNotNull(target.parentFile))
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= size) { "전달된 파일이 예상 크기를 초과합니다." }
                output.write(buffer, 0, count)
            }
            require(total == size) { "전달된 파일 크기가 다릅니다." }
            output.fd.sync()
        }
        syncDirectory(requireNotNull(target.parentFile))
        patchVerify(target, size, sha)
    }

    private fun durableCopy(source: File, target: File) {
        source.inputStream().use { receive(it, target, source.length(), patchSha(source)) }
    }

    private fun atomicJson(target: File, value: JSONObject) = atomic(target, value.toString().toByteArray(Charsets.UTF_8))

    private fun atomic(target: File, bytes: ByteArray) {
        val parent = requireNotNull(target.parentFile)
        mkdir(parent)
        val temp = patchChild(parent, "${target.name}.tmp")
        FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(parent)
    }

    private fun mkdir(directory: File) {
        if (directory.isDirectory) return
        val parent = requireNotNull(directory.parentFile)
        mkdir(parent)
        require(directory.mkdir()) { "작업 디렉터리를 만들 수 없습니다: $directory" }
        syncDirectory(parent)
    }

    private fun remove(file: File) {
        require(!Files.isSymbolicLink(file.toPath())) { "작업 디렉터리에 심볼릭 링크가 있습니다." }
        if (file.isDirectory) requireNotNull(file.listFiles()).forEach(::remove)
        require(!file.exists() || file.delete()) { "작업 파일을 정리할 수 없습니다: $file" }
        file.parentFile?.let(syncDirectory)
    }

    private fun readJson(file: File): JSONObject {
        require(file.isFile && file.length() <= MAX_HISTORY_BYTES)
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun safeId(id: String): String = id.also { require(it.matches(Regex("^[0-9a-f-]{36}$"))) }

    companion object { private const val MAX_HISTORY_BYTES = 4 * 1024 * 1024L }
}
