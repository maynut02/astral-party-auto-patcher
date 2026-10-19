package com.maynutlab.astralpatcher.ui

import com.maynutlab.astralpatcher.core.PatchProtocol
import com.maynutlab.astralpatcher.core.ReleaseEntry
import com.maynutlab.astralpatcher.core.PatchTargetInspection
import com.maynutlab.astralpatcher.shizuku.GameResourceLocator
import com.maynutlab.astralpatcher.shizuku.inspectPlannedTargets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResourceVersionMismatchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val catalogHash = "a".repeat(32)
    private val cacheHash = "b".repeat(32)
    private val dynamicSetting = "UNRESOLVED_CACHE_OVERRIDE: {App.WebServerConfig.CacheDirectoryPath}; dynamic properties are not evaluated"

    /** Exercise actual file discovery, hashing, service diagnosis and response parsing together. */
    private fun inspect(
        contents: List<String> = List(3) { "original-$it" },
        warnings: List<String> = listOf(dynamicSetting),
        observedHash: String = catalogHash,
        duplicateFirst: Boolean = false,
    ): PatchTargetInspection {
        val root = temporary.newFolder()
        val files = JSONArray()
        repeat(3) { index ->
            val path = "bundle$index/$cacheHash/__data"
            contents.getOrNull(index)?.let { content ->
                File(root, "downloaded-cache/$path").apply {
                    requireNotNull(parentFile).mkdirs()
                    writeText(content)
                }
            }
            if (index == 0 && duplicateFirst) {
                File(root, "other-cache/$path").apply {
                    requireNotNull(parentFile).mkdirs()
                    writeText("original-0")
                }
            }
            val original = "original-$index".toByteArray()
            val payload = "patched-$index".toByteArray()
            files.put(JSONObject().put("path", path)
                .put("sourceSize", original.size).put("sourceSha256", PatchProtocol.sha256(original))
                .put("payloadSize", payload.size).put("payloadSha256", PatchProtocol.sha256(payload)))
        }
        val request = JSONObject().put("schemaVersion", 2).put("route", "INT_ANDROID")
            .put("operation", "PATCH").put("gameVersion", "3.3.0").put("catalogHash", catalogHash)
            .put("patchVersion", "v3.3.0_r026_p0").put("manifestSha256", "c".repeat(64)).put("files", files)
        val locator = GameResourceLocator(root)
        return PatchProtocol.parseTargetInspection(inspectPlannedTargets(
            root, "com.feimo.astralpartyjpn", request, observedHash, warnings,
        ) { path -> locator.findTargets(path).let { it.files to it.errors } }.toString())
    }

    private fun latest() = ReleaseEntry(
        "INT_ANDROID", "3.3.0", "026", catalogHash, "v3.3.0_r026_p0",
        "https://example.test/manifest.json", "c".repeat(64),
    )

    @Test fun matchingResourcesInstallAndReinstallWithoutConfirmation() {
        for (contents in listOf(List(3) { "original-$it" }, List(3) { "patched-$it" })) {
            assertNull(resourceVersionMismatch(inspect(contents), "3.3.0", latest()))
        }
    }

    @Test fun olderTranslationContentsDoNotChangeTheResourceVersion() {
        assertNull(resourceVersionMismatch(inspect(listOf("old translation", "original-1", "original-2")), "3.3.0", latest()))
        // A translation p-number changes no game-resource identity.
        assertNull(resourceVersionMismatch(inspect(), "3.3.0", latest().copy(patchVersion = "v3.3.0_r026_p10")))
    }

    @Test fun differentResourceCatalogRequiresConfirmationForInstallAndRestore() {
        assertNotNull(resourceVersionMismatch(inspect(observedHash = "d".repeat(32)), "3.3.0", latest()))
        assertNotNull(resourceVersionMismatch(inspect(List(3) { "patched-$it" }, observedHash = "d".repeat(32)), "3.3.0", latest()))
    }

    @Test fun differentGameResourceVersionRequiresConfirmation() {
        assertNotNull(resourceVersionMismatch(inspect(), "3.2.0", latest()))
    }

    @Test fun diagnosticsAndIncompleteCatalogRecordsAreNotResourceVersionDifferences() {
        val observed = inspect(warnings = listOf(dynamicSetting, "CATALOG_BUNDLE_RECORDS_UNCONFIRMED: no complete catalog"))
            .copy(catalogMatches = false)
        assertNull(resourceVersionMismatch(observed, "3.3.0", latest()))
        assertNull(resourceVersionMismatch(observed.copy(catalogHash = null), null, latest()))
    }

    @Test fun missingOrDuplicateTargetsCannotBeBypassedByMatchingResourceVersion() {
        for (observed in listOf(inspect(contents = listOf("original-0", "original-1")), inspect(duplicateFirst = true))) {
            assertThrows(IllegalArgumentException::class.java) { resourceVersionMismatch(observed, "3.3.0", latest()) }
        }
    }
}
