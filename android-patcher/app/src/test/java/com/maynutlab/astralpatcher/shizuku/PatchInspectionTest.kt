package com.maynutlab.astralpatcher.shizuku

import org.json.JSONArray
import org.json.JSONObject
import com.maynutlab.astralpatcher.core.PatchProtocol
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PatchInspectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val packageName = "com.feimo.astralpartyjpn"
    private val hash = "a".repeat(32)
    private val path = "bundle/$hash/__data"

    private fun request(schema: Int = 1): JSONObject = JSONObject().put("schemaVersion", schema).put("packageName", packageName)
        .put("route", "INT_ANDROID").put("operation", "PATCH").put("gameVersion", "3.3.0")
        .put("catalogHash", "b".repeat(32)).put("patchVersion", "release").put("manifestSha256", "c".repeat(64))
        .put("files", JSONArray().put(JSONObject().put("path", path).put("sourceSize", 100).put("sourceSha256", "d".repeat(64))
            .put("payloadSize", 101).put("payloadSha256", "e".repeat(64))))

    @Test fun unknownModifiedFileWithDifferentCatalogStillProducesPinnedPlan() {
        val root = temporary.newFolder()
        val target = File(root, "dynamic/$path").apply { parentFile.mkdirs(); writeText("legitimate older patch") }
        val result = inspectPlannedTargets(root, packageName, request(2), hash, emptyList()) { listOf(target) to emptyList() }
        assertEquals(2, result.getInt("schemaVersion"))
        assertEquals(0, result.getInt("missing"))
        assertEquals(1, result.getInt("incompatible"))
        assertFalse(result.getBoolean("catalogMatches"))
        val observation = result.getJSONArray("files").getJSONObject(0)
        assertEquals("UNKNOWN_MODIFIED", observation.getString("state"))
        assertTrue(observation.isNull("error"))
        assertEquals("dynamic/$path", observation.getString("actualPath"))
        assertEquals(observation.getString("actualPath"), observation.getJSONArray("candidates").getString(0))
        val plan = PlannedPatch.parse(packageName, result.getJSONObject("plan").toString())
        assertEquals(1, result.getJSONObject("plan").getInt("schemaVersion"))
        assertEquals("b".repeat(32), plan.catalogHash)
        assertEquals(hash, plan.observedCatalogHash)
        assertEquals(patchSha(target), plan.targets.single().observedSha256)
        val inspection = PatchProtocol.parseTargetInspection(result.toString())
        assertTrue(inspection.canWrite)
        assertFalse(inspection.isReady)
        assertNotNull(inspection.planJson)
        assertFalse(File(root, ".astralpatch-manager").exists())
    }

    @Test fun noCatalogCanProduceExecutablePlanWithNullObservedHash() {
        val root = temporary.newFolder()
        val target = File(root, path).apply { parentFile.mkdirs(); writeText("existing") }
        val result = inspectPlannedTargets(root, packageName, request(), null, emptyList()) { listOf(target) to emptyList() }
        assertTrue(result.isNull("observedCatalogHash"))
        assertNull(PlannedPatch.parse(packageName, result.getJSONObject("plan").toString()).observedCatalogHash)
    }

    @Test fun missingFilesDoNotProduceExecutablePlanOrUnreadableCount() {
        val result = inspectPlannedTargets(temporary.newFolder(), packageName, request(), hash, emptyList()) {
            emptyList<File>() to listOf("NOT_FOUND: game has not downloaded this bundle")
        }
        assertEquals(1, result.getInt("missing"))
        assertEquals(0, result.getInt("unreadable"))
        assertEquals("MISSING", result.getJSONArray("files").getJSONObject(0).getString("state"))
        assertTrue(result.isNull("plan"))
        val inspection = PatchProtocol.parseTargetInspection(result.toString())
        assertFalse(inspection.canWrite)
        assertNull(inspection.planJson)
    }

    @Test fun permissionAndScanLimitErrorsNeverBecomeDownloadMissing() {
        listOf("ACCESS_DENIED: directory", "LIMIT_ENTRIES: scan incomplete", "IO: read failed").forEach { error ->
            val result = inspectPlannedTargets(temporary.newFolder(), packageName, request(), hash, emptyList()) {
                emptyList<File>() to listOf("NOT_FOUND: partial result", error)
            }
            assertEquals(0, result.getInt("missing"))
            assertEquals(1, result.getInt("unreadable"))
            assertTrue(result.isNull("plan"))
            assertTrue(result.getJSONArray("files").getJSONObject(0).getString("error").contains(error))
        }
    }

    @Test fun twoAliasesAreAmbiguousEvenWhenExactFilenameExists() {
        val root = temporary.newFolder()
        val exact = File(root, path).apply { parentFile.mkdirs(); writeText("one") }
        val alternate = File(root, "${path}__").apply { writeText("two") }
        val result = inspectPlannedTargets(root, packageName, request(), hash, emptyList()) {
            listOf(exact, alternate) to listOf("AMBIGUOUS_DATA_NAMES")
        }
        assertEquals(1, result.getInt("ambiguous"))
        assertEquals(0, result.getInt("unreadable"))
        assertTrue(result.isNull("plan"))
        assertEquals(2, result.getJSONArray("files").getJSONObject(0).getJSONArray("candidates").length())
    }

    @Test fun alternateNameIsPinnedWithoutSubstitution() {
        val root = temporary.newFolder()
        val alternate = File(root, "cache/${path}__").apply { parentFile.mkdirs(); writeText("alternate") }
        val result = inspectPlannedTargets(root, packageName, request(), hash, emptyList()) { listOf(alternate) to emptyList() }
        assertEquals("cache/${path}__", PlannedPatch.parse(packageName, result.getJSONObject("plan").toString()).targets.single().actualPath)
    }

    @Test fun foundCandidateWithDisappearedSubtreeIsUnreadableAndCannotProducePlan() {
        val root = temporary.newFolder()
        val target = File(root, path).apply { parentFile.mkdirs(); writeText("exists") }
        val result = inspectPlannedTargets(root, packageName, request(), hash, emptyList()) {
            listOf(target) to listOf("NOT_FOUND: another subtree disappeared during traversal")
        }
        assertEquals(1, result.getInt("unreadable"))
        assertEquals(0, result.getInt("missing"))
        assertTrue(result.isNull("plan"))
    }

    @Test fun nullAndBlankHistoryPatchVersionsAreAbsentAndOtherTypesAreCorrupt() {
        listOf(JSONObject.NULL, "", "   ").forEach { value ->
            val file = temporary.newFile().apply {
                writeText(JSONObject().put("schemaVersion", 1).put("catalogHash", hash).put("patchVersion", value).toString())
            }
            val history = inspectPatchHistory(file, hash)
            assertEquals("OK", history.getString("status"))
            assertEquals("", history.getString("patchVersion"))
        }
        val file = temporary.newFile().apply {
            writeText(JSONObject().put("schemaVersion", 1).put("catalogHash", hash).put("patchVersion", 123).toString())
        }
        assertEquals("CORRUPT", inspectPatchHistory(file, hash).getString("status"))
    }

    @Test fun historyFromOtherCatalogPreservesPatchVersion() {
        val file = temporary.newFile().apply { writeText("""{"schemaVersion":1,"patchVersion":"older-patch","catalogHash":"${"b".repeat(32)}"}""") }
        val history = inspectPatchHistory(file, hash)
        assertEquals("OTHER_CATALOG", history.getString("status"))
        assertEquals("older-patch", history.getString("patchVersion"))
    }

    @Test fun truncatedHistoryPreservesRecoverablePatchVersionAndReturnsError() {
        val file = temporary.newFile().apply { writeText("""{"patchVersion":"older-patch","broken": """) }
        val before = file.readText()
        val history = inspectPatchHistory(file, null)
        assertEquals("CORRUPT", history.getString("status"))
        assertEquals("older-patch", history.getString("patchVersion"))
        assertFalse(history.isNull("error"))
        assertEquals(before, file.readText())
    }

    @Test fun absentHistoryDoesNotCreateAnyFile() {
        val root = temporary.newFolder()
        val file = File(root, ".astralpatch-manager/state.json")
        assertEquals("NONE", inspectPatchHistory(file, null).getString("status"))
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun requestRejectsWrongRouteAndSecondaryProfile() {
        fails { validateReleaseRequest(packageName, request().put("route", "CN_ANDROID")) }
        fails { validateReleaseRequest(packageName, request().put("apkSnapshot", JSONObject().put("userId", 10).toString())) }
    }

    @Test fun preservesFullApkSnapshotAsOpaqueJsonString() {
        val root = temporary.newFolder()
        val target = File(root, path).apply { parentFile.mkdirs(); writeText("data") }
        val snapshot = JSONObject().put("packageName", packageName).put("versionName", JSONObject.NULL).put("versionCode", 3300)
            .put("lastUpdateTime", 123L).put("sourceDir", "/data/app/game/base.apk")
            .put("splitSourceDirs", JSONArray().put("/data/app/game/split.apk")).put("userId", 0).toString()
        val result = inspectPlannedTargets(root, packageName, request().put("apkSnapshot", snapshot), hash, emptyList()) { listOf(target) to emptyList() }
        val plan = PlannedPatch.parse(packageName, result.getJSONObject("plan").toString())
        assertEquals(snapshot, plan.apkSnapshot)
        assertEquals(listOf("/data/app/game/base.apk", "/data/app/game/split.apk"), apkPaths(JSONObject(plan.apkSnapshot!!)))
    }

    @Test fun completeSelectedCatalogRejectsAbsentBundleRecordAsCompatibilityEvidence() {
        val candidate = CatalogCandidate("3.3.0", null, hash, null, "JSON_PARSED", true,
            bundles = listOf(CatalogBundle("other-bundle", hash, "unused")), bundleRecordsComplete = true)
        val catalog = CatalogObservation("3.3.0", hash, emptyList(), listOf(candidate))
        val (matches, warnings) = patchCatalogEvidence(catalog, request().getJSONArray("files"))
        assertFalse(matches)
        assertTrue(warnings.single().startsWith("CATALOG_TARGET_MISMATCH"))
    }

    @Test fun partialCatalogRecordsDoNotClaimRequestedBundleIsAbsent() {
        val candidate = CatalogCandidate("3.3.0", null, hash, null, "UNREADABLE_OR_INVALID", true, bundleRecordsComplete = false)
        val (matches, warnings) = patchCatalogEvidence(CatalogObservation("3.3.0", hash, emptyList(), listOf(candidate)), request().getJSONArray("files"))
        assertTrue(matches)
        assertTrue(warnings.single().startsWith("CATALOG_BUNDLE_RECORDS_UNCONFIRMED"))
    }

    @Test fun completeSelectedCatalogMatchesFullBundleNameAndCacheHash() {
        val candidate = CatalogCandidate("3.3.0", null, hash, null, "JSON_PARSED", true,
            bundles = listOf(CatalogBundle("bundle", hash, "unused")), bundleRecordsComplete = true)
        val result = patchCatalogEvidence(CatalogObservation("3.3.0", hash, emptyList(), listOf(candidate)), request().getJSONArray("files"))
        assertTrue(result.first)
        assertTrue(result.second.isEmpty())
    }

    @Test fun unsafePathsAndMismatchedActualTargetsAreRejected() {
        listOf("../escape", "/absolute", "C:/drive", "file\u0000name", ".astralpatch-manager/../../state.json").forEach { path ->
            fails { patchChild(temporary.root, path) }
        }
        val root = temporary.newFolder()
        val target = File(root, path).apply { parentFile.mkdirs(); writeText("data") }
        val result = inspectPlannedTargets(root, packageName, request(), hash, emptyList()) { listOf(target) to emptyList() }
        val plan = result.getJSONObject("plan")
        plan.getJSONArray("targets").getJSONObject(0).put("actualPath", "other-bundle/$hash/__data")
        fails { PlannedPatch.parse(packageName, plan.toString()) }
    }

    private fun fails(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("expected rejection")
    }
}
