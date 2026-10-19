package com.maynutlab.astralpatcher.shizuku

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameResourceLocatorTest {
    @get:Rule val temporary = TemporaryFolder()

    private val root: File by lazy { temporary.newFolder("files") }
    private val hash = "842e7aef365f0c11507ee32a7e71533a"
    private val otherHash = "1a8b575cfbede4526512d9cbe5044ffe"
    private val bundle = "9c2c156995397d00b52292463263f219"
    private val cacheHash = "71085ed3370baca1773b66592991407c"
    private val relative get() = "$bundle/$cacheHash/__data"

    @Test fun dynamicOverrideFindsActualCacheWithoutEvaluatingGameProperties() {
        val apk = apk(settings(override = "{App.WebServerConfig.CacheDirectoryPath}"))
        val target = write("changed/download-cache/$relative")
        catalog("3.3.0")
        val locator = GameResourceLocator(root, listOf(apk.path))

        assertEquals(listOf(target), locator.findTargets(relative).files)
        assertTrue(locator.findTargets(relative).errors.isEmpty())
        val observed = locator.inspectCatalogs()
        assertEquals(hash, observed.selectedHash)
        assertCode(observed.warnings, "UNRESOLVED_CACHE_OVERRIDE")
    }

    @Test fun staticOverrideAndRelocatedCacheHashPathAreInspected() {
        val apk = apk(settings(
            catalogPath = "{UnityEngine.Application.persistentDataPath}/download/catalog_3.3.0.hash",
            override = "{UnityEngine.Application.persistentDataPath}/download/bundles",
        ))
        write("download/catalog_3.3.0.hash", hash)
        val target = write("download/bundles/$relative")
        val locator = GameResourceLocator(root, listOf(apk.path))

        assertEquals(hash, locator.inspectCatalogs().selectedHash)
        assertCode(locator.inspectCatalogs().warnings, "APK_CACHE_OVERRIDE")
        assertEquals(listOf(target), locator.findTargets(relative).files)
    }

    @Test fun returnsAllRootDuplicatesEvenWhenApkHintsAtOneOfThem() {
        val apk = apk(settings(override = "{UnityEngine.Application.persistentDataPath}/cache-a"))
        val first = write("cache-a/$relative")
        val second = write("cache-b/$relative")

        val result = GameResourceLocator(root, listOf(apk.path)).findTargets(relative)

        assertEquals(setOf(first, second), result.files.toSet())
        assertCode(result.errors, "AMBIGUOUS_CACHE_ROOTS")
    }

    @Test fun dualDataNamesRemainAmbiguousInEitherManifestSpelling() {
        val first = write("cache/$relative")
        val second = write("cache/${relative}__")
        val locator = GameResourceLocator(root)

        for (path in listOf(relative, "${relative}__")) {
            val result = locator.findTargets(path)
            assertEquals(setOf(first, second), result.files.toSet())
            assertCode(result.errors, "AMBIGUOUS_DATA_NAMES")
        }
    }

    @Test fun acceptsAlternateDataNameButNeverFallsBackAcrossHashDirectories() {
        val alternate = write("cache/${relative}__")
        val locator = GameResourceLocator(root)
        assertEquals(listOf(alternate), locator.findTargets(relative).files)
        assertTrue(locator.findTargets(relative).errors.isEmpty())

        val missing = locator.findTargets("$bundle/$otherHash/__data")
        assertTrue(missing.files.isEmpty())
        assertCode(missing.errors, "NOT_FOUND")
    }

    @Test fun completeSuffixDoesNotMatchSimilarBundleNamesOrOutsideFilesRoot() {
        write("cache/prefix-$bundle/$cacheHash/__data")
        val sibling = temporary.newFolder("other-package")
        File(sibling, relative).apply { requireNotNull(parentFile).mkdirs(); writeText("outside") }
        val result = GameResourceLocator(root).findTargets(relative)

        assertTrue(result.files.isEmpty())
        assertCode(result.errors, "NOT_FOUND")
    }

    @Test fun managerBackupsAndTransactionsAreExcludedAtEveryDepth() {
        write(".astralpatch-manager/backups/$relative")
        write("cache/.astralpatch-manager/previous/$relative")
        write(".astralpatch-manager/catalog_3.3.0.hash", hash)
        val locator = GameResourceLocator(root)

        assertCode(locator.findTargets(relative).errors, "NOT_FOUND")
        assertTrue(locator.inspectCatalogs().candidates.isEmpty())
    }

    @Test fun unsafeManifestPathsAreRejectedBeforeWalking() {
        val invalid = listOf(
            "../$cacheHash/__data", "/$relative", "cache/$relative", "$bundle/../__data",
            "$bundle/$cacheHash/anything", "$bundle/not-a-hash/__data", relative.replace('/', '\\'),
            ".astralpatch-manager/$cacheHash/__data", "bundle:stream/$cacheHash/__data",
        )
        for (path in invalid) {
            val result = GameResourceLocator(root).findTargets(path)
            assertTrue(path, result.files.isEmpty())
            assertCode(result.errors, "INVALID_PATH")
        }
    }

    @Test fun apkExpectedCatalogWinsOverNumericallyGreaterStaleCatalog() {
        catalog("3.3.0")
        catalog("99.0.0", otherHash)
        val apk = apk(settings())

        val result = GameResourceLocator(root, listOf(apk.path)).inspectCatalogs()

        assertEquals("3.3.0", result.selectedVersion)
        assertEquals(hash, result.selectedHash)
        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.single { it.version == "3.3.0" }.expectedByApk)
    }

    @Test fun missingExpectedCatalogNeverSelectsTheOnlyStaleCatalog() {
        catalog("3.2.0", otherHash)
        val apk = apk(settings())
        val result = GameResourceLocator(root, listOf(apk.path)).inspectCatalogs()

        assertNull(result.selectedVersion)
        assertNull(result.selectedHash)
        assertCode(result.warnings, "EXPECTED_CATALOG_MISSING")
    }

    @Test fun multipleCatalogsWithoutApkEvidenceAreNotNumericallyRanked() {
        catalog("3.2.0", otherHash)
        catalog("3.3.0")
        val result = GameResourceLocator(root).inspectCatalogs()

        assertNull(result.selectedHash)
        assertCode(result.warnings, "AMBIGUOUS_CATALOG")
    }

    @Test fun duplicateExpectedCatalogPathsRemainAmbiguousEvenWithIdenticalHashes() {
        catalog("3.3.0")
        write("other/catalog_3.3.0.hash", hash)
        val apk = apk(settings())
        val result = GameResourceLocator(root, listOf(apk.path)).inspectCatalogs()

        assertEquals(2, result.candidates.size)
        assertNull(result.selectedHash)
        assertCode(result.warnings, "AMBIGUOUS_CATALOG")
    }

    @Test fun splitApksAreReadAndConflictingExpectedNamesAreNotCollapsed() {
        catalog("3.3.0")
        val baseWithoutSettings = apk(null)
        val split = apk(settings())
        assertEquals(hash, GameResourceLocator(root, listOf(baseWithoutSettings.path, split.path))
            .inspectCatalogs().selectedHash)

        val conflicting = apk(settings(catalogPath = "{UnityEngine.Application.persistentDataPath}/catalog_3.2.0.hash"))
        val result = GameResourceLocator(root, listOf(split.path, conflicting.path)).inspectCatalogs()
        assertNull(result.selectedHash)
        assertCode(result.warnings, "AMBIGUOUS_CATALOG")
    }

    @Test fun unreadableOrMissingApkDoesNotTurnAStaleCatalogIntoConfirmedIdentity() {
        catalog("3.3.0")
        val missing = File(temporary.root, "missing.apk")
        val result = GameResourceLocator(root, listOf(missing.path)).inspectCatalogs()

        assertNull(result.selectedHash)
        assertCode(result.warnings, "NOT_FOUND")
        assertEquals(hash, result.candidates.single().hash)
    }

    @Test fun malformedJsonPreservesHashWithASeparateParsingWarning() {
        catalog("3.3.0")
        write("com.unity.addressables/catalog_3.3.0.json", "{broken:")
        val result = GameResourceLocator(root).inspectCatalogs()

        assertEquals(hash, result.selectedHash)
        assertEquals("UNREADABLE_OR_INVALID", result.candidates.single().jsonStatus)
        assertCode(result.warnings, "INVALID_JSON")
    }

    @Test fun buildResultHashAndJsonBytesAreNotComparedToCatalogHash() {
        catalog("3.3.0")
        write("com.unity.addressables/catalog_3.3.0.json", bundleCatalog().put("m_BuildResultHash", otherHash).toString())
        val result = GameResourceLocator(root).inspectCatalogs()

        assertEquals(hash, result.selectedHash)
        assertEquals("JSON_PARSED", result.candidates.single().jsonStatus)
        assertEquals(listOf(CatalogBundle(bundle, cacheHash, "{App.WebServerConfig.Path}/$cacheHash.bundle")),
            result.candidates.single().bundles)
        assertTrue(result.candidates.single().bundleRecordsComplete)
        assertFalse(result.warnings.any { it.contains("INVALID_HASH") })
    }

    @Test fun invalidSerializedTableIsReportedWithoutLosingHashIdentity() {
        catalog("3.3.0")
        val json = bundleCatalog().put("m_EntryDataString", Base64.getEncoder().encodeToString(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MAX_VALUE).array(),
        ))
        write("com.unity.addressables/catalog_3.3.0.json", json.toString())
        val result = GameResourceLocator(root).inspectCatalogs()

        assertEquals(hash, result.selectedHash)
        assertCode(result.warnings, "INVALID_CATALOG_TABLE")
        assertTrue(result.candidates.single().bundles.isEmpty())
        assertFalse(result.candidates.single().bundleRecordsComplete)
    }

    @Test fun jsonOnlyAndBinaryOnlyCatalogsRemainDiagnosticCandidates() {
        write("catalog_3.2.0.json", "{}")
        write("catalog_3.3.0.bundle", "binary-not-decoded")
        val result = GameResourceLocator(root).inspectCatalogs()

        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.all { it.hash == null })
        assertCode(result.warnings, "CATALOG_HASH_MISSING")
        assertCode(result.warnings, "CATALOG_BUNDLE_UNSUPPORTED")
    }

    @Test fun localCatalogsLargerThanHttpFourMiBLimitAreRead() {
        catalog("3.3.0")
        val json = bundleCatalog().put("padding", " ".repeat(4 * 1024 * 1024 + 100)).toString()
        write("com.unity.addressables/catalog_3.3.0.json", json)
        val result = GameResourceLocator(root).inspectCatalogs()

        assertEquals(hash, result.selectedHash)
        assertEquals(1, result.candidates.single().bundles.size)
        assertFalse(result.warnings.any { it.startsWith("LIMIT_") })
    }

    @Test fun catalogSizeAndJsonNestingHaveIndependentBounds() {
        catalog("3.3.0")
        val file = write("com.unity.addressables/catalog_3.3.0.json")
        RandomAccessFile(file, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        val oversized = GameResourceLocator(root).inspectCatalogs()
        assertEquals(hash, oversized.selectedHash)
        assertCode(oversized.warnings, "LIMIT_FILE_BYTES")

        file.writeText("{\"nested\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}")
        val nested = GameResourceLocator(root).inspectCatalogs()
        assertEquals(hash, nested.selectedHash)
        assertCode(nested.warnings, "LIMIT_JSON_DEPTH")
    }

    @Test fun oversizedCompressedApkSettingsCannotBeDecompressedWithoutBound() {
        catalog("3.3.0")
        val apk = apk(" ".repeat(1024 * 1024 + 1))
        val result = GameResourceLocator(root, listOf(apk.path)).inspectCatalogs()

        assertNull(result.selectedHash)
        assertCode(result.warnings, "LIMIT_SETTINGS_BYTES")
    }

    @Test fun unsafeConfiguredRootsAreWarningsAndDoNotBroadenPackageScope() {
        val sibling = temporary.newFolder("outside-cache")
        File(sibling, relative).apply { requireNotNull(parentFile).mkdirs(); writeText("outside") }
        val apk = apk(settings(override = sibling.absolutePath.replace('\\', '/')))
        catalog("3.3.0")
        val locator = GameResourceLocator(root, listOf(apk.path))

        assertCode(locator.inspectCatalogs().warnings, "UNSAFE_CACHE_OVERRIDE")
        assertCode(locator.findTargets(relative).errors, "NOT_FOUND")
    }

    @Test fun oversizedZipDirectoryIsRejectedBeforeZipFileIndexesIt() {
        catalog("3.3.0")
        val archive = temporary.newFile("oversized-directory.apk")
        val tail = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        tail.putInt(0x06054b50).putShort(0).putShort(0).putShort(1).putShort(1)
            .putInt(33 * 1024 * 1024).putInt(0).putShort(0)
        archive.writeBytes(tail.array())
        val result = GameResourceLocator(root, listOf(archive.path)).inspectCatalogs()

        assertNull(result.selectedHash)
        assertCode(result.warnings, "LIMIT_ZIP_DIRECTORY_BYTES")
    }

    @Test fun countLimitMakesIncompleteSearchAnError() {
        write("cache/$relative")
        val locator = limited(GameResourceLocator.ScanLimits(maxEntries = 2))
        val result = locator.findTargets(relative)

        assertCode(result.errors, "LIMIT_ENTRIES")
        assertFalse(result.errors.any { it.startsWith("NOT_FOUND:") })
    }

    @Test fun depthLimitPreservesVisibleCandidateButPreventsSilentSelection() {
        val shallow = write(relative)
        write("deep/another/cache/$relative")
        val result = limited(GameResourceLocator.ScanLimits(maxDepth = 2)).findTargets(relative)

        assertEquals(listOf(shallow), result.files)
        assertCode(result.errors, "LIMIT_DEPTH")
    }

    @Test fun timeLimitIsDeterministicAndReportedAsUncertainty() {
        write("cache/$relative")
        var ticks = 0L
        val locator = GameResourceLocator(root, emptyList(), GameResourceLocator.ScanLimits(maxMillis = 1)) {
            (ticks++) * 1_000_000L
        }
        val result = locator.findTargets(relative)

        assertCode(result.errors, "LIMIT_TIME")
        assertFalse(result.errors.any { it.startsWith("NOT_FOUND:") })
    }

    @Test fun catalogTraversalLimitPreventsAutomaticIdentitySelection() {
        catalog("3.3.0")
        write("deep/one/two/something")
        val result = limited(GameResourceLocator.ScanLimits(maxDepth = 1)).inspectCatalogs()

        assertEquals(hash, result.candidates.single().hash)
        assertNull(result.selectedHash)
        assertCode(result.warnings, "LIMIT_DEPTH")
    }

    @Test fun totalReadAndCatalogCountBudgetsAreReported() {
        catalog("3.3.0")
        val readLimited = limited(GameResourceLocator.ScanLimits(maxReadBytes = 16)).inspectCatalogs()
        assertCode(readLimited.warnings, "LIMIT_READ_BYTES")
        assertNull(readLimited.selectedHash)

        catalog("3.2.0", otherHash)
        val countLimited = limited(GameResourceLocator.ScanLimits(maxCatalogs = 1)).inspectCatalogs()
        assertNull(countLimited.selectedHash)
        assertCode(countLimited.warnings, "LIMIT_CATALOGS")
    }

    @Test fun missingRootIsDistinctFromAnExistingNonDirectory() {
        val absent = File(temporary.root, "absent")
        assertCode(GameResourceLocator(absent).findTargets(relative).errors, "NOT_FOUND")
        val file = temporary.newFile("not-directory")
        assertCode(GameResourceLocator(file).findTargets(relative).errors, "NOT_DIRECTORY")
    }

    @Test fun symlinksCannotEscapeOrCreateAdditionalInScopeCandidates() {
        val outside = temporary.newFolder("outside")
        File(outside, relative).apply { requireNotNull(parentFile).mkdirs(); writeText("outside") }
        val inside = write("real/$relative")
        createLink(File(root, "escape"), outside)
        createLink(File(root, "alias"), File(root, "real"))

        val result = GameResourceLocator(root).findTargets(relative)

        assertEquals(listOf(inside), result.files)
        assertCode(result.errors, "SYMLINK_REJECTED")
    }

    @Test fun symlinkedRootAndCatalogHashAreRejected() {
        catalog("3.3.0")
        val link = File(temporary.root, "linked-root")
        createLink(link, root)
        assertCode(GameResourceLocator(link).findTargets(relative).errors, "SYMLINK_REJECTED")

        val outside = temporary.newFile("outside.hash").apply { writeText(otherHash) }
        createLink(File(root, "catalog_9.0.0.hash"), outside)
        val result = GameResourceLocator(root).inspectCatalogs()
        assertEquals(1, result.candidates.size)
        assertNull(result.selectedHash)
        assertCode(result.warnings, "SYMLINK_REJECTED")
    }

    @Test fun permissionFailureIsNeverReportedAsAResourceDownloadAbsence() {
        val target = write("cache/$relative")
        val path = requireNotNull(target.parentFile).toPath()
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        if (acl != null) {
            val original = acl.acl
            try {
                val denial = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(acl.owner)
                    .setPermissions(AclEntryPermission.LIST_DIRECTORY).build()
                acl.acl = listOf(denial) + original
                val result = GameResourceLocator(root).findTargets(relative)
                assertCode(result.errors, "ACCESS_DENIED")
                assertFalse(result.errors.any { it.startsWith("NOT_FOUND:") })
            } finally {
                acl.acl = original
            }
            return
        }
        assumeTrue("Permission test requires POSIX or ACL support", Files.getFileStore(path).supportsFileAttributeView("posix"))
        val original = Files.getPosixFilePermissions(path)
        try {
            Files.setPosixFilePermissions(path, emptySet<PosixFilePermission>())
            assumeFalse("Privileged process bypasses permission denial", Files.isReadable(path))
            val result = GameResourceLocator(root).findTargets(relative)
            assertCode(result.errors, "ACCESS_DENIED")
            assertFalse(result.errors.any { it.startsWith("NOT_FOUND:") })
        } finally {
            Files.setPosixFilePermissions(path, original)
        }
    }

    private fun limited(limits: GameResourceLocator.ScanLimits) =
        GameResourceLocator(root, emptyList(), limits, System::nanoTime)

    private fun write(path: String, content: String = "data"): File = File(root, path).apply {
        requireNotNull(parentFile).mkdirs()
        writeText(content)
    }

    private fun catalog(version: String, value: String = hash) =
        write("com.unity.addressables/catalog_$version.hash", value)

    private fun settings(
        catalogPath: String = "{UnityEngine.Application.persistentDataPath}/com.unity.addressables/catalog_3.3.0.hash",
        override: String = "{App.WebServerConfig.CacheDirectoryPath}",
    ): String = JSONObject()
        .put("m_CatalogLocations", JSONArray().put(JSONObject()
            .put("m_Keys", JSONArray().put("AddressablesMainContentCatalogCacheHash"))
            .put("m_InternalId", catalogPath)))
        .put("m_ExtraInitializationData", JSONArray().put(JSONObject()
            .put("m_Id", "CacheInitialization")
            .put("m_Data", JSONObject().put("m_CacheDirectoryOverride", override).toString())))
        .toString()

    private fun apk(settings: String?): File = temporary.newFile("apk-${System.nanoTime()}.apk").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(if (settings == null) "unrelated.txt" else "assets/aa/settings.json"))
            zip.write((settings ?: "split without settings").toByteArray())
            zip.closeEntry()
        }
    }

    private fun bundleCatalog(): JSONObject {
        val extra = ByteArrayOutputStream()
        extra.write(7)
        val assembly = "Unity.ResourceManager".toByteArray(Charsets.US_ASCII)
        extra.write(assembly.size)
        extra.write(assembly)
        val type = "UnityEngine.ResourceManagement.ResourceProviders.AssetBundleRequestOptions".toByteArray(Charsets.US_ASCII)
        extra.write(type.size)
        extra.write(type)
        val options = JSONObject().put("m_BundleName", bundle).put("m_Hash", cacheHash).toString().toByteArray(Charsets.UTF_16LE)
        extra.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(options.size).array())
        extra.write(options)
        val entries = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        for (number in intArrayOf(1, 0, 0, -1, 0, 0, 0, 0)) entries.putInt(number)
        return JSONObject()
            .put("m_InternalIds", JSONArray().put("{App.WebServerConfig.Path}/$cacheHash.bundle"))
            .put("m_ProviderIds", JSONArray().put("AssetBundleProvider"))
            .put("m_resourceTypes", JSONArray().put(JSONObject().put("m_ClassName",
                "UnityEngine.ResourceManagement.ResourceProviders.IAssetBundleResource")))
            .put("m_EntryDataString", Base64.getEncoder().encodeToString(entries.array()))
            .put("m_ExtraDataString", Base64.getEncoder().encodeToString(extra.toByteArray()))
    }

    private fun createLink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic link creation is unavailable on this host", error)
        }
    }

    private fun assertCode(messages: List<String>, code: String) {
        assertTrue("Expected $code in $messages", messages.any { it.startsWith("$code:") })
    }
}
