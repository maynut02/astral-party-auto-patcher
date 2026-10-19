package com.maynutlab.astralpatcher.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PatchInspectionProtocolTest {
    @Test
    fun unknownCatalogRetainsIndependentInstallHistoryAndDiagnostics() {
        val result = PatchProtocol.parseInspection(JSONObject()
            .put("schemaVersion", 2).put("gameVersion", "").put("catalogHash", "")
            .put("patchVersion", "v3.3.0_r014_p0").put("historyStatus", "DIFFERENT_CATALOG")
            .put("historyError", "history warning").put("diagnostic", "catalog unavailable").toString())
        assertEquals("", result.gameVersion)
        assertEquals("", result.catalogHash)
        assertEquals("v3.3.0_r014_p0", result.installedPatchVersion)
        assertEquals("DIFFERENT_CATALOG", result.historyStatus)
        assertEquals("history warning", result.historyError)
        assertEquals("catalog unavailable", result.diagnostic)
    }

    @Test
    fun nullableInspectionFieldsDoNotBecomeLiteralNullStrings() {
        val result = PatchProtocol.parseInspection("""{"schemaVersion":2,"catalogHash":null,"patchVersion":null}""")
        assertEquals("", result.gameVersion)
        assertEquals("", result.catalogHash)
        assertNull(result.installedPatchVersion)
        assertEquals("UNKNOWN", result.historyStatus)
        assertEquals("", result.historyError)
        assertEquals("", result.diagnostic)
    }

    @Test
    fun serviceWarningsRemainVisibleWhenThereIsNoCombinedDiagnostic() {
        val json = JSONObject().put("schemaVersion", 2).put("catalogHash", "")
            .put("warnings", JSONArray(listOf("catalog unavailable", "pending recovery")))
        assertEquals("catalog unavailable\npending recovery", PatchProtocol.parseInspection(json.toString()).diagnostic)
    }

    @Test
    fun combinesExplicitDiagnosisWarningsAndPendingRecoveryWithoutLosingCorruptHistory() {
        val root = JSONObject().put("schemaVersion", 2).put("gameVersion", "").put("catalogHash", "")
            .put("patchVersion", "recorded-patch").put("historyStatus", "CORRUPT")
            .put("historyError", "cannot parse history record").put("diagnostic", "catalog lookup failed")
            .put("warnings", JSONArray(listOf("EACCES: catalog root", "catalog lookup failed")))
            .put("recoveryPending", true).put("pendingRecovery", JSONObject().put("pending", true))
        val result = PatchProtocol.parseInspection(root.toString())
        assertTrue(result.diagnostic.startsWith("catalog lookup failed\nEACCES: catalog root\n"))
        assertTrue(result.diagnostic.contains("복구"))
        assertEquals("CORRUPT", result.historyStatus)
        assertEquals("cannot parse history record", result.historyError)
        assertEquals("recorded-patch", result.installedPatchVersion)
        assertEquals("", result.catalogHash)
    }

    @Test
    fun longDiagnosisIsBoundedWithoutDiscardingIndependentHistory() {
        val root = JSONObject().put("schemaVersion", 2).put("catalogHash", "")
            .put("patchVersion", "recorded-patch").put("historyStatus", "CORRUPT").put("historyError", "bad record")
            .put("diagnostic", "explicit").put("warnings", JSONArray(listOf("x".repeat(10000))))
        val result = PatchProtocol.parseInspection(root.toString())
        assertEquals(4096, result.diagnostic.length)
        assertTrue(result.diagnostic.startsWith("explicit\n"))
        assertEquals("recorded-patch", result.installedPatchVersion)
        assertEquals("bad record", result.historyError)
    }

    @Test
    fun legacyInspectionRemainsStrictAndNonEmptyCatalogStillRequiresValidDigest() {
        val legacy = """{"schemaVersion":1,"gameVersion":"3.3.0","catalogHash":"${"B".repeat(32)}","patchVersion":"old"}"""
        assertEquals("b".repeat(32), PatchProtocol.parseInspection(legacy).catalogHash)
        assertEquals("old", PatchProtocol.parseInspection(legacy).installedPatchVersion)
        for (json in listOf(
            legacy.replace("B".repeat(32), ""),
            legacy.replace("\"schemaVersion\":1", "\"schemaVersion\":2").replace("B".repeat(32), "invalid"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { PatchProtocol.parseInspection(json) }
        }
    }

    @Test
    fun schemaTwoPreservesEveryFileStateAndObservedCatalogSeparatelyFromPlan() {
        val observed = "c".repeat(32)
        val plan = plan().put("observedCatalogHash", observed)
        val root = inspection("ORIGINAL", "CURRENT_PATCH", "KNOWN_OLDER_PATCH", "UNKNOWN_MODIFIED",
            "MISSING", "UNREADABLE", "AMBIGUOUS")
            .put("observedCatalogHash", observed).put("catalogMatches", false)
            .put("warnings", JSONArray(listOf("catalog differs", "unknown modified file")))
            .put("plan", plan)
        val result = PatchProtocol.parseTargetInspection(root.toString())
        assertEquals(7, result.total)
        assertEquals(3, result.ready)
        assertEquals(1, result.missing)
        assertEquals(1, result.incompatible)
        assertEquals(1, result.unreadable)
        assertEquals(1, result.ambiguous)
        assertEquals(observed, result.catalogHash)
        assertFalse(result.catalogMatches)
        assertEquals(listOf("catalog differs", "unknown modified file"), result.warnings)
        assertEquals(listOf("ORIGINAL", "CURRENT_PATCH", "KNOWN_OLDER_PATCH", "UNKNOWN_MODIFIED",
            "MISSING", "UNREADABLE", "AMBIGUOUS"), result.files.map { it.state })
        assertEquals(listOf("/cache/6/__data", "/cache/6/__data__"), result.files.last().candidates)
        assertEquals("EACCES", result.files[5].error)
        val retainedPlan = JSONObject(requireNotNull(result.planJson))
        for (key in listOf("schemaVersion", "operation", "route", "gameVersion", "catalogHash",
            "patchVersion", "manifestSha256", "observedCatalogHash", "apkSnapshot")) {
            assertEquals(key, plan.get(key), retainedPlan.get(key))
        }
        val expectedTarget = plan.getJSONArray("targets").getJSONObject(0)
        val retainedTarget = retainedPlan.getJSONArray("targets").getJSONObject(0)
        assertEquals(1, retainedPlan.getJSONArray("targets").length())
        for (key in listOf("path", "actualPath", "observedSha256", "sourceSha256", "payloadSha256")) {
            assertEquals(key, expectedTarget.getString(key), retainedTarget.getString(key))
        }
        for (key in listOf("observedSize", "sourceSize", "payloadSize")) {
            assertEquals(key, expectedTarget.getLong(key), retainedTarget.getLong(key))
        }
        assertEquals("b".repeat(32), retainedPlan.getString("catalogHash"))
        assertFalse(result.isReady)
        assertFalse(result.canWrite)
    }

    @Test
    fun unknownModifiedIsWritableButNeverRelabeledAsKnownOldPatch() {
        val result = PatchProtocol.parseTargetInspection(inspection("UNKNOWN_MODIFIED").toString())
        assertEquals("UNKNOWN_MODIFIED", result.files.single().state)
        assertEquals(0, result.ready)
        assertEquals(1, result.incompatible)
        assertTrue(result.canWrite)
        assertFalse(result.isReady)
    }

    @Test
    fun knownStatesAreReadyOnlyWhenCatalogMatches() {
        val root = inspection("ORIGINAL", "CURRENT_PATCH", "KNOWN_OLDER_PATCH")
        val ready = PatchProtocol.parseTargetInspection(root.toString())
        assertTrue(ready.isReady)
        assertTrue(ready.canWrite)
        val mismatch = PatchProtocol.parseTargetInspection(root.put("catalogMatches", false).toString())
        assertFalse(mismatch.isReady)
        assertTrue(mismatch.canWrite)
    }

    @Test
    fun unknownObservedCatalogDoesNotPreventWritingVerifiedUniqueFiles() {
        val root = inspection("CURRENT_PATCH").put("observedCatalogHash", JSONObject.NULL)
            .put("catalogMatches", false)
        val result = PatchProtocol.parseTargetInspection(root.toString())
        assertNull(result.catalogHash)
        assertTrue(result.canWrite)
        assertFalse(result.isReady)
        assertThrows(IllegalArgumentException::class.java) {
            PatchProtocol.parseTargetInspection(root.put("catalogMatches", true).toString())
        }
    }

    @Test
    fun missingUnreadableAndAmbiguousTargetsCannotBeWritten() {
        for (state in listOf("MISSING", "UNREADABLE", "AMBIGUOUS")) {
            val result = PatchProtocol.parseTargetInspection(inspection(state).toString())
            assertFalse(state, result.canWrite)
            assertFalse(state, result.isReady)
        }
    }

    @Test
    fun nullableDiagnosticStringsAreEmptyAndSizeRemainsUnknown() {
        val root = inspection("MISSING")
        root.getJSONArray("files").getJSONObject(0).put("actualPath", JSONObject.NULL)
            .put("sha256", JSONObject.NULL).put("size", JSONObject.NULL).put("error", JSONObject.NULL)
        val file = PatchProtocol.parseTargetInspection(root.toString()).files.single()
        assertEquals("", file.actualPath)
        assertEquals("", file.sha256)
        assertEquals("", file.error)
        assertNull(file.size)
    }

    @Test
    fun duplicateActualOrLogicalPathsDoNotGrantReadinessOrWriteability() {
        for (field in listOf("path", "actualPath")) {
            val root = inspection("CURRENT_PATCH", "CURRENT_PATCH")
            val files = root.getJSONArray("files")
            val firstValue = files.getJSONObject(0).getString(field)
            files.getJSONObject(1).put(field, firstValue)
            if (field == "actualPath") files.getJSONObject(1).put("candidates", JSONArray(listOf(firstValue)))
            val result = PatchProtocol.parseTargetInspection(root.toString())
            assertFalse(field, result.canWrite)
            assertFalse(field, result.isReady)
        }
    }

    @Test
    fun incompleteObservationsAndReadErrorsCannotGrantReadiness() {
        for ((key, value) in listOf(
            "actualPath" to "", "sha256" to "", "size" to JSONObject.NULL, "error" to "EIO",
            "candidates" to JSONArray(listOf("/cache/0/__data", "/cache/0/__data__")),
        )) {
            val root = inspection("ORIGINAL")
            root.getJSONArray("files").getJSONObject(0).put(key, value)
            val result = PatchProtocol.parseTargetInspection(root.toString())
            assertFalse(key, result.canWrite)
            assertFalse(key, result.isReady)
        }
    }

    @Test
    fun missingAndJsonNullPlansAreOptionalAndRemainNull() {
        val root = inspection("ORIGINAL")
        assertNull(PatchProtocol.parseTargetInspection(root.toString()).planJson)
        root.put("plan", JSONObject.NULL)
        assertNull(PatchProtocol.parseTargetInspection(root.toString()).planJson)
    }

    @Test
    fun refusesUnknownFileStatesAndInconsistentSummaryCounts() {
        val unknown = inspection("ORIGINAL")
        unknown.getJSONArray("files").getJSONObject(0).put("state", "READY")
        val wrongCounts = inspection("UNKNOWN_MODIFIED").put("ready", 1).put("incompatible", 0)
        val wrongLength = inspection("ORIGINAL").put("files", JSONArray())
        for (root in listOf(unknown, wrongCounts, wrongLength, inspection("ORIGINAL").put("unreadable", -1))) {
            assertThrows(IllegalArgumentException::class.java) { PatchProtocol.parseTargetInspection(root.toString()) }
        }
    }

    @Test
    fun summaryValidationDoesNotOverflowIntoValidCount() {
        assertThrows(IllegalArgumentException::class.java) {
            PatchProtocol.parseTargetInspection("""{"schemaVersion":1,"total":1,"ready":2147483647,"missing":2147483647,"incompatible":3}""")
        }
    }

    @Test
    fun originalConstructorAndLegacyResultsRemainCompatible() {
        assertTrue(PatchTargetInspection(1, 1, 0, 0).isReady)
        assertTrue(PatchTargetInspection(1, 1, 0, 0).canWrite)
        val legacy = PatchProtocol.parseTargetInspection("""{"schemaVersion":1,"total":1,"ready":0,"missing":0,"incompatible":1}""")
        assertFalse(legacy.isReady)
        assertFalse(legacy.canWrite) // Old INCOMPATIBLE never proved that the file was readable.
        assertEquals(emptyList<PatchTargetDiagnostic>(), legacy.files)
        assertNull(legacy.planJson)
    }

    @Test
    fun requestPinsReleaseDigestAndPreservesApkSnapshotStringForBothOperations() {
        val snapshot = """{ "packageName": "game", "versionCode": 3300, "lastUpdateTime": 42 }"""
        for (operation in listOf("PATCH", "RESTORE")) {
            val request = JSONObject(PatchProtocol.createTargetInspectionRequest(manifest(), release(), operation, snapshot))
            assertEquals(2, request.getInt("schemaVersion"))
            assertEquals("INT_ANDROID", request.getString("route"))
            assertEquals("3.3.0", request.getString("gameVersion"))
            assertEquals("b".repeat(32), request.getString("catalogHash"))
            assertEquals("v3.3.0_r014_p3", request.getString("patchVersion"))
            assertEquals("a".repeat(64), request.getString("manifestSha256"))
            assertEquals(operation, request.getString("operation"))
            assertEquals(snapshot, request.getString("apkSnapshot"))
            val target = request.getJSONArray("files").getJSONObject(0)
            assertEquals("bundle/hash/__data", target.getString("path"))
            assertEquals(100L, target.getLong("sourceSize"))
            assertEquals("1".repeat(64), target.getString("sourceSha256"))
            assertEquals(120L, target.getLong("payloadSize"))
            assertEquals("2".repeat(64), target.getString("payloadSha256"))
        }
        val defaults = JSONObject(PatchProtocol.createTargetInspectionRequest(manifest()))
        assertEquals("PATCH", defaults.getString("operation"))
        assertEquals("", defaults.getString("manifestSha256"))
        assertTrue(defaults.isNull("apkSnapshot"))
    }

    @Test
    fun requestRejectsReleaseMetadataMismatchUnsupportedOperationsAndDuplicatePaths() {
        for (release in listOf(
            release().copy(route = "CN_ANDROID"), release().copy(gameVersion = "3.2.0"),
            release().copy(revision = "014"), release().copy(catalogHash = "c".repeat(32)),
            release().copy(patchVersion = "v3.3.0_r014_p4"), release().copy(manifestSha256 = "invalid"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PatchProtocol.createTargetInspectionRequest(manifest(), release)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PatchProtocol.createTargetInspectionRequest(manifest(), release(), "DELETE")
        }
        assertThrows(IllegalArgumentException::class.java) {
            val manifest = manifest()
            PatchProtocol.createTargetInspectionRequest(manifest.copy(files = manifest.files + manifest.files))
        }
    }

    @Test
    fun oversizedSnapshotCannotExceedExistingBinderRequestLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            PatchProtocol.createTargetInspectionRequest(manifest(), release(), apkSnapshot = "x".repeat(256 * 1024))
        }
    }

    private fun inspection(vararg states: String): JSONObject {
        val files = states.mapIndexed { index, state ->
            val readable = state in listOf("ORIGINAL", "CURRENT_PATCH", "KNOWN_OLDER_PATCH", "UNKNOWN_MODIFIED")
            val actual = "/cache/$index/__data"
            JSONObject().put("path", "bundle/$index/__data").put("state", state)
                .put("actualPath", if (readable) actual else "")
                .put("size", if (readable) 100L else JSONObject.NULL)
                .put("sha256", if (readable) "a".repeat(64) else "")
                .put("error", if (state == "UNREADABLE") "EACCES" else "")
                .put("candidates", JSONArray(when {
                    state == "AMBIGUOUS" -> listOf(actual, "${actual}__")
                    readable -> listOf(actual)
                    else -> emptyList()
                }))
        }
        return JSONObject().put("schemaVersion", 2).put("total", states.size)
            .put("ready", states.count { it in listOf("ORIGINAL", "CURRENT_PATCH", "KNOWN_OLDER_PATCH") })
            .put("missing", states.count { it == "MISSING" })
            .put("incompatible", states.count { it == "UNKNOWN_MODIFIED" })
            .put("unreadable", states.count { it == "UNREADABLE" })
            .put("ambiguous", states.count { it == "AMBIGUOUS" })
            .put("files", JSONArray(files)).put("observedCatalogHash", "b".repeat(32))
            .put("catalogMatches", true).put("warnings", JSONArray())
    }

    private fun plan() = JSONObject().put("schemaVersion", 1).put("operation", "PATCH")
        .put("route", "INT_ANDROID").put("gameVersion", "3.3.0").put("catalogHash", "b".repeat(32))
        .put("patchVersion", "v3.3.0_r014_p3").put("manifestSha256", "a".repeat(64))
        .put("observedCatalogHash", JSONObject.NULL).put("apkSnapshot", "{\"versionCode\":3300}")
        .put("targets", JSONArray().put(JSONObject()
            .put("path", "bundle/hash/__data").put("actualPath", "/cache/hash/__data")
            .put("observedSize", 110L).put("observedSha256", "c".repeat(64))
            .put("sourceSize", 100L).put("sourceSha256", "1".repeat(64))
            .put("payloadSize", 120L).put("payloadSha256", "2".repeat(64))))

    private fun release() = ReleaseEntry("INT_ANDROID", "3.3.0", "012", "b".repeat(32),
        "v3.3.0_r014_p3", "https://github.com/maynut02/astral-party-korean-patch/releases/download/v3.3.0_r014_p3/INT_ANDROID_manifest.json",
        "a".repeat(64))

    private fun manifest() = PatchManifest("INT_ANDROID", "v3.3.0_r014_p3", "3.3.0", "012", "b".repeat(32),
        listOf(PatchFile("bundle/hash/__data", "unused", "3".repeat(64), 80,
            "2".repeat(64), 120, "unused", "4".repeat(64), 70, "1".repeat(64), 100)))
}
