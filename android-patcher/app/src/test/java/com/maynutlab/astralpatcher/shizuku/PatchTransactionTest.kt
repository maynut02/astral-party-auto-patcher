package com.maynutlab.astralpatcher.shizuku

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class PatchTransactionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val packageName = "com.feimo.astralpartyjpn"
    private val id = "00000000-0000-0000-0000-000000000001"
    private val originals = listOf("release original one", "release original two")
    private val payloads = listOf("patched one", "patched two")

    private inner class Fixture(operation: PatchOperation = PatchOperation.PATCH, history: Boolean = true) {
        val root = temporary.newFolder()
        val stateRoot = File(root, ".astralpatch-manager")
        val state = File(stateRoot, "state.json")
        val priorHistory = "{\"schemaVersion\":1,\"patchVersion\":\"older\",\"catalogHash\":\"${"b".repeat(32)}\"}"
        val before = listOf("unknown old patch one", "unknown old patch two")
        val files = before.mapIndexed { index, value ->
            File(root, "dynamic-cache/bundle$index/${"a".repeat(32)}/__data").apply { requireNotNull(parentFile).mkdirs(); writeText(value) }
        }
        val plan = PlannedPatch(
            packageName, operation, "INT_ANDROID", "3.3.0", "b".repeat(32), "selected-release", "d".repeat(64), "c".repeat(32),
            files.mapIndexed { index, file ->
                PlannedTarget("bundle$index/${"a".repeat(32)}/__data", file.relativeTo(root).invariantSeparatorsPath,
                    file.length(), patchSha(file), originals[index].toByteArray().size.toLong(), sha(originals[index]),
                    payloads[index].toByteArray().size.toLong(), sha(payloads[index]))
            },
            JSONObject().put("packageName", packageName).put("versionName", JSONObject.NULL).put("versionCode", 3300)
                .put("lastUpdateTime", 123L).put("sourceDir", "/data/app/game/base.apk").put("splitSourceDirs", JSONArray())
                .put("userId", 0).toString(),
        )
        init { if (history) { stateRoot.mkdirs(); state.writeText(priorHistory) } }
        fun engine(writer: (File, File) -> Unit = ::overwrite, sync: (File) -> Unit = {}) = PatchTransaction(root, packageName, writer, sync)
        fun apply(engine: PatchTransaction, index: Int) {
            val target = plan.targets[index]
            val content = if (plan.operation == PatchOperation.PATCH) payloads[index] else originals[index]
            engine.apply(id, target.path, input(content), target.outputSize(plan.operation), target.outputSha(plan.operation), target.sourceSize, target.sourceSha256)
        }
        fun commit(engine: PatchTransaction) = engine.commit(id, plan.gameVersion, plan.catalogHash, plan.patchVersion)
        fun assertBefore() { files.forEachIndexed { index, file -> assertEquals(before[index], file.readText()) } }
        fun assertAfter() { files.forEachIndexed { index, file -> assertEquals(if (plan.operation == PatchOperation.PATCH) payloads[index] else originals[index], file.readText()) } }
        fun assertNoSavedCopies() {
            assertFalse(File(stateRoot, "backups").exists())
            assertTrue(File(stateRoot, "transactions").listFiles().orEmpty().isEmpty())
            assertEquals(listOf("state.json", "transactions"), stateRoot.list().orEmpty().sorted())
        }
    }

    @Test fun beginSavesEveryRollbackCopyAndExactPriorHistoryBeforeAnyWrite() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        fixture.assertBefore()
        fixture.files.forEachIndexed { index, _ -> assertEquals(fixture.before[index], File(engine.directory(id), "previous/$index").readText()) }
        assertEquals(fixture.priorHistory, File(engine.directory(id), "previous-state.bin").readText())
        assertTrue(File(engine.directory(id), "READY").isFile)
        assertEquals(fixture.plan, engine.plan(id))
    }

    @Test fun installsWithoutReleaseOriginalsAndPinsReleaseSeparatelyFromObservedCatalog() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        fixture.commit(engine)
        fixture.assertAfter()
        val state = JSONObject(fixture.state.readText())
        assertEquals(fixture.plan.catalogHash, state.getString("catalogHash"))
        assertEquals(fixture.plan.observedCatalogHash, state.getString("observedCatalogHash"))
        assertEquals(fixture.plan.manifestSha256, state.getString("manifestSha256"))
        assertEquals(fixture.plan.apkSnapshot, state.getString("apkSnapshot"))
        assertEquals(fixture.plan.patchVersion, state.getString("patchVersion"))
        assertEquals(fixture.plan.targets[1].actualPath, state.getJSONArray("files").getJSONObject(1).getString("actualPath"))
        assertFalse(engine.directory(id).exists())
        fixture.assertNoSavedCopies()
    }

    @Test fun restoresWithoutAnyInstallHistoryAndRecordsVerifiedSourceOutputs() {
        val fixture = Fixture(PatchOperation.RESTORE, history = false)
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        fixture.commit(engine)
        fixture.assertAfter()
        val state = JSONObject(fixture.state.readText())
        assertEquals("", state.getString("patchVersion"))
        assertEquals("RESTORE", state.getString("operation"))
        assertEquals(fixture.plan.targets[0].sourceSha256, state.getJSONArray("files").getJSONObject(0).getString("sha256"))
        assertEquals("RESTORED", inspectPatchHistory(fixture.state, fixture.plan.observedCatalogHash).getString("status"))
        fixture.assertNoSavedCopies()
    }

    @Test fun rejectsChangedObservedTargetBeforeCreatingTransaction() {
        val fixture = Fixture()
        fixture.files[1].writeText("updated by game")
        val engine = fixture.engine()
        fails { engine.begin(id, fixture.plan) }
        assertFalse(engine.directory(id).exists())
        assertEquals(fixture.before[0], fixture.files[0].readText())
    }

    @Test fun patchNeverReadsOrReplacesLegacyPermanentOriginals() {
        val fixture = Fixture()
        val target = fixture.plan.targets[0]
        val legacyOriginal = File(fixture.stateRoot, "backups/${fixture.plan.manifestSha256}/${target.sourceSha256}")
        requireNotNull(legacyOriginal.parentFile).mkdirs()
        legacyOriginal.writeText("corrupt legacy original must not block installation")
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        fixture.commit(engine)
        fixture.assertAfter()
        assertEquals("corrupt legacy original must not block installation", legacyOriginal.readText())
        assertEquals(listOf(legacyOriginal), File(fixture.stateRoot, "backups").walkTopDown().filter { it.isFile }.toList())
        assertTrue(File(fixture.stateRoot, "transactions").listFiles().orEmpty().isEmpty())
    }

    @Test fun rejectsCorruptStreamWithoutTouchingTargets() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        val target = fixture.plan.targets[0]
        fails { engine.apply(id, target.path, input("x".repeat(payloads[0].length)), target.payloadSize, target.payloadSha256, target.sourceSize, target.sourceSha256) }
        fixture.assertBefore()
        assertEquals(0, JSONObject(File(engine.directory(id), "journal.json").readText()).getJSONArray("attempted").length())
    }

    @Test fun partialWriteDuringPatchRecoversEveryTargetAndPriorHistoryOnNewService() = partialFailure(PatchOperation.PATCH)
    @Test fun partialWriteDuringRestoreRecoversEveryTargetAndPriorHistoryOnNewService() = partialFailure(PatchOperation.RESTORE)

    private fun partialFailure(operation: PatchOperation) {
        val fixture = Fixture(operation)
        var count = 0
        val failing = fixture.engine(writer = { source, target ->
            count++
            if (count == 2) {
                FileOutputStream(target).use { it.write(byteArrayOf(9)); it.fd.sync() }
                throw IOException("simulated partial overwrite")
            }
            overwrite(source, target)
        })
        failing.begin(id, fixture.plan)
        fixture.apply(failing, 0)
        fails { fixture.apply(failing, 1) }
        val journal = JSONObject(File(failing.directory(id), "journal.json").readText())
        assertEquals(2, journal.getJSONArray("attempted").length())
        assertEquals(fixture.plan.targets[1].actualPath, journal.getJSONObject("plan").getJSONArray("targets").getJSONObject(1).getString("actualPath"))
        fixture.engine().recoverAll()
        fixture.assertBefore()
        assertEquals(fixture.priorHistory, fixture.state.readText())
        assertFalse(failing.directory(id).exists())
    }

    @Test fun incompleteCommitFailsAndRollbackPreservesExistingHistory() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.begin(id, fixture.plan); fixture.apply(engine, 0)
        fails { fixture.commit(engine) }
        assertEquals(fixture.priorHistory, fixture.state.readText())
        engine.rollback(id)
        fixture.assertBefore()
        assertEquals(fixture.priorHistory, fixture.state.readText())
        fixture.assertNoSavedCopies()
    }

    @Test fun commitRehashesEveryExpectedOutputBeforePublishingHistory() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        fixture.files[0].writeText("changed after successful apply")
        fails { fixture.commit(engine) }
        assertFalse(File(engine.directory(id), "COMMITTED").exists())
        assertEquals(fixture.priorHistory, fixture.state.readText())
        fixture.engine().recoverAll()
        fixture.assertBefore()
    }

    @Test fun commitRejectsCallerReleaseSubstitution() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        fails { engine.commit(id, fixture.plan.gameVersion, fixture.plan.observedCatalogHash!!, fixture.plan.patchVersion) }
        assertEquals(fixture.priorHistory, fixture.state.readText())
    }

    @Test fun recoveryValidatesAllRollbackCopiesBeforeRestoringFirstFile() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        File(engine.directory(id), "previous/0").writeText("corrupt backup")
        fails { fixture.engine().recoverAll() }
        fixture.assertAfter()
        assertTrue(File(engine.directory(id), "journal.json").isFile)
        assertEquals(fixture.priorHistory, fixture.state.readText())
    }

    @Test fun durableCommitMarkerCompletesPublicationInsteadOfRollingBack() {
        val fixture = Fixture()
        val engine = fixture.engine(sync = { dir ->
            if (dir.name == id && File(dir, "COMMITTED").isFile) throw IOException("crash after commit marker")
        })
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        fails { fixture.commit(engine) }
        assertTrue(File(engine.directory(id), "COMMITTED").isFile)
        assertEquals(fixture.priorHistory, fixture.state.readText())
        val recovering = fixture.engine(writer = { _, _ -> fail("committed transaction must never roll back") })
        recovering.recoverAll()
        fixture.assertAfter()
        assertEquals(fixture.plan.patchVersion, JSONObject(fixture.state.readText()).getString("patchVersion"))
    }

    @Test fun crashDuringCommittedCleanupCannotLoseCommitMarkerAndUndoFiles() {
        val fixture = Fixture()
        val engine = fixture.engine(sync = { dir ->
            if (dir.name == "transactions" && dir.listFiles().orEmpty().any { it.name.endsWith(".finished") }) {
                throw IOException("crash immediately after cleanup rename")
            }
        })
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        fails { fixture.commit(engine) }
        fixture.engine(writer = { _, _ -> fail("cleanup must not write game files") }).recoverAll()
        fixture.assertAfter()
        assertEquals(fixture.plan.patchVersion, JSONObject(fixture.state.readText()).getString("patchVersion"))
    }

    @Test fun interruptedRollbackCanBeRetriedAfterPartialRollbackWrite() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        val interrupted = fixture.engine(writer = { source, target ->
            overwrite(source, target)
            throw IOException("crash during rollback")
        })
        fails { interrupted.recoverAll() }
        fixture.engine().recoverAll()
        fixture.assertBefore()
        assertEquals(fixture.priorHistory, fixture.state.readText())
    }

    @Test fun partialPreparationIsCleanedWithoutWritingGameFilesOrHistory() {
        val fixture = Fixture()
        val engine = fixture.engine(sync = { dir ->
            if (dir.name == "previous" && File(dir, "1").isFile) throw IOException("crash before READY")
        })
        fails { engine.begin(id, fixture.plan) }
        fixture.engine(writer = { _, _ -> fail("unprepared transaction must not overwrite") }).recoverAll()
        fixture.assertBefore()
        assertEquals(fixture.priorHistory, fixture.state.readText())
        assertFalse(engine.directory(id).exists())
    }

    @Test fun journalPathTraversalIsRejectedWithoutLosingRecoveryFiles() {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.begin(id, fixture.plan); fixture.apply(engine, 0)
        val journalFile = File(engine.directory(id), "journal.json")
        val journal = JSONObject(journalFile.readText())
        journal.getJSONObject("plan").getJSONArray("targets").getJSONObject(0).put("actualPath", "../outside")
        journalFile.writeText(journal.toString())
        fails { fixture.engine().recoverAll() }
        assertTrue(File(engine.directory(id), "previous/0").isFile)
        assertEquals(payloads[0], fixture.files[0].readText())
        assertEquals(fixture.priorHistory, fixture.state.readText())
    }

    @Test fun restoreRejectsPatchPayloadMetadataBeforeWriting() {
        val fixture = Fixture(PatchOperation.RESTORE)
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        val target = fixture.plan.targets[0]
        fails { engine.apply(id, target.path, input(payloads[0]), target.payloadSize, target.payloadSha256, target.sourceSize, target.sourceSha256) }
        fixture.assertBefore()
    }

    @Test fun restoreRejectsCorruptSourceBytesBeforeWritingWithoutPermanentOriginals() {
        val fixture = Fixture(PatchOperation.RESTORE, history = false)
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        val target = fixture.plan.targets[0]
        fails { engine.apply(id, target.path, input("x".repeat(originals[0].length)), target.sourceSize, target.sourceSha256, target.sourceSize, target.sourceSha256) }
        fixture.assertBefore()
        assertFalse(fixture.state.exists())
        assertFalse(File(fixture.stateRoot, "backups").exists())
        assertEquals(0, JSONObject(File(engine.directory(id), "journal.json").readText()).getJSONArray("attempted").length())
        engine.rollback(id)
        assertTrue(File(fixture.stateRoot, "transactions").listFiles().orEmpty().isEmpty())
    }

    @Test fun rollbackAfterRestoreWithoutHistoryRestoresObservedBytesAndDeletesTemporaryCopies() {
        val fixture = Fixture(PatchOperation.RESTORE, history = false)
        val engine = fixture.engine()
        engine.begin(id, fixture.plan)
        fixture.files.indices.forEach { fixture.apply(engine, it) }
        fixture.assertAfter()
        engine.rollback(id)
        fixture.assertBefore()
        assertFalse(fixture.state.exists())
        assertFalse(File(fixture.stateRoot, "backups").exists())
        assertTrue(File(fixture.stateRoot, "transactions").listFiles().orEmpty().isEmpty())
        assertEquals(listOf("transactions"), fixture.stateRoot.list().orEmpty().toList())
    }

    @Test fun newRecoveryNeverDeletesAnUnrecognizedLegacyJournal() {
        val fixture = Fixture()
        val engine = fixture.engine()
        val transaction = engine.directory(id)
        transaction.mkdirs()
        File(transaction, "catalog.txt").writeText("b".repeat(32))
        File(transaction, "journal.txt").writeText(fixture.plan.targets[0].path)
        File(transaction, "previous").mkdirs()
        File(transaction, "previous/saved").writeText("legacy rollback copy")
        fails { engine.recoverAll() }
        assertEquals("legacy rollback copy", File(transaction, "previous/saved").readText())
        fixture.assertBefore()
    }

    private fun sha(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun input(value: String) = ByteArrayInputStream(value.toByteArray())
    private fun overwrite(source: File, target: File) {
        require(target.isFile)
        source.inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } }
    }
    private fun fails(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("expected rejection/failure")
    }
}
