package com.maynutlab.astralpatcher.shizuku

import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.zip.ZipFile

data class CatalogObservation(
    val selectedVersion: String?,
    val selectedHash: String?,
    val warnings: List<String>,
    val candidates: List<CatalogCandidate> = emptyList(),
)

/** JSON_PARSED means syntax was read, not that the entire Addressables key graph was validated. */
data class CatalogCandidate(
    val version: String,
    val hashFile: File?,
    val hash: String?,
    val jsonFile: File?,
    val jsonStatus: String,
    val expectedByApk: Boolean,
    val bundles: List<CatalogBundle> = emptyList(),
    // Only absence in a complete bundle-option scan is evidence that a bundle record is missing.
    val bundleRecordsComplete: Boolean = false,
    val warnings: List<String> = emptyList(),
)

/** bundleName/cacheHash match the first two manifest path components; internalId is never evaluated. */
data class CatalogBundle(val bundleName: String, val cacheHash: String, val internalId: String)

data class TargetCandidates(val files: List<File>, val errors: List<String>)

/**
 * Read-only observations, not proof of the catalog/cache currently loaded by the game.
 * Every call scans afresh. A target is usable only after the caller has checked both
 * files.size == 1 and errors.isEmpty(), and revalidated it before writing.
 * Diagnostic strings start with a stable code (NOT_FOUND, ACCESS_DENIED, LIMIT_*, etc.).
 */
class GameResourceLocator internal constructor(
    filesRoot: File,
    private val apkPaths: List<String>,
    private val limits: ScanLimits,
    private val nanoTime: () -> Long,
) {
    constructor(filesRoot: File, apkPaths: List<String> = emptyList()) :
        this(filesRoot, apkPaths, ScanLimits(), System::nanoTime)

    internal data class ScanLimits(
        val maxDepth: Int = 16,
        val maxEntries: Int = 60_000,
        val maxMillis: Long = 5_000,
        val maxCatalogs: Int = 32,
        val maxReadBytes: Long = 64L * 1024 * 1024,
    ) {
        init {
            require(maxDepth >= 0 && maxEntries > 0 && maxMillis > 0)
            require(maxCatalogs > 0 && maxReadBytes > 0)
        }
    }

    private val root = filesRoot.toPath().toAbsolutePath().normalize()

    fun inspectCatalogs(): CatalogObservation {
        val scan = Scan()
        val settings = readSettings(scan)
        val catalogFiles = linkedMapOf<Path, MutableMap<String, File>>()
        scan.walk { path ->
            val match = CATALOG_NAME.matchEntire(path.fileName.toString()) ?: return@walk
            val stem = path.resolveSibling("catalog_${match.groupValues[1]}")
            if (stem !in catalogFiles && catalogFiles.size >= limits.maxCatalogs) {
                throw Problem("LIMIT_CATALOGS", "More than ${limits.maxCatalogs} catalogs")
            }
            catalogFiles.getOrPut(stem) { linkedMapOf() }[match.groupValues[2]] = path.toFile()
        }
        val candidates = catalogFiles.entries.sortedBy { it.key.toString() }.map { (stem, files) ->
            val warnings = mutableListOf<String>()
            val hashFile = files["hash"]
            val hash = hashFile?.let {
                scan.attempt(warnings, it.path) {
                    scan.read(it.toPath(), MAX_HASH_BYTES).trim().removePrefix("\uFEFF").trim().also { value ->
                        if (!HASH.matches(value)) throw Problem("INVALID_HASH", "Expected a 32-digit catalog identifier")
                    }.lowercase()
                }
            }
            if (hashFile == null) warnings += "CATALOG_HASH_MISSING: $stem.hash"
            val jsonFile = files["json"]
            var status = "MISSING"
            val bundles = mutableListOf<CatalogBundle>()
            var bundleRecordsComplete = false
            if (jsonFile != null) {
                status = "UNREADABLE_OR_INVALID"
                scan.attempt(warnings, jsonFile.path) {
                    val json = parseJson(scan.read(jsonFile.toPath(), MAX_CATALOG_BYTES), scan)
                    status = "JSON_PARSED"
                    inspectBundleIds(json, bundles, scan)
                    bundleRecordsComplete = true
                }
            } else {
                warnings += if (files["bundle"] != null) {
                    "CATALOG_BUNDLE_UNSUPPORTED: ${files["bundle"]}; binary catalog is not decoded"
                } else {
                    "CATALOG_JSON_MISSING: $stem.json; hash identity is unverified by bundle records"
                }
            }
            CatalogCandidate(
                version = stem.fileName.toString().removePrefix("catalog_"),
                hashFile = hashFile,
                hash = hash,
                jsonFile = jsonFile,
                jsonStatus = status,
                expectedByApk = "${stem.fileName}.hash" in settings.expectedNames,
                bundles = bundles.distinct(),
                bundleRecordsComplete = bundleRecordsComplete,
                warnings = warnings,
            )
        }
        val warnings = (settings.warnings + scan.errors + candidates.flatMap { it.warnings }).toMutableList()
        val eligible = if (settings.expectedNames.isEmpty()) candidates else candidates.filter { it.expectedByApk }
        if (settings.expectedNames.isNotEmpty() && eligible.isEmpty()) {
            warnings += "EXPECTED_CATALOG_MISSING: ${settings.expectedNames.joinToString()}; stale catalogs are not selected"
        }
        if (settings.expectedNames.size > 1 || eligible.size > 1) {
            warnings += "AMBIGUOUS_CATALOG: conflicting APK names or multiple local candidates; no automatic selection"
        }
        if (settings.expectedNames.isEmpty()) {
            warnings += "APK_CATALOG_UNCONFIRMED: a unique local catalog does not establish the active game catalog"
        }
        val selected = eligible.singleOrNull()?.takeIf {
            settings.complete && settings.expectedNames.size <= 1 && scan.errors.isEmpty()
        }
        return CatalogObservation(selected?.version, selected?.hash, warnings.distinct(), candidates)
    }

    /** Match the complete manifest suffix; never substitute a different hash directory. */
    fun findTargets(relativePath: String): TargetCandidates {
        val parts = relativePath.split('/')
        if (parts.size != 3 || !SAFE_NAME.matches(parts[0]) || !HASH.matches(parts[1]) ||
            parts[2] !in DATA_NAMES || parts.any { it == "." || it == ".." || it == MANAGER }
        ) {
            return TargetCandidates(emptyList(), listOf("INVALID_PATH: Expected bundleName/hash/__data or __data__"))
        }
        val scan = Scan()
        val found = linkedSetOf<File>()
        scan.walk { path ->
            if (path.fileName.toString() in DATA_NAMES &&
                path.parent?.fileName?.toString() == parts[1] &&
                path.parent?.parent?.fileName?.toString() == parts[0] &&
                root.relativize(path).nameCount >= 3
            ) {
                found += path.toFile()
                // Opening with NOFOLLOW_LINKS distinguishes unreadable files from absent ones.
                scan.attempt(scan.errors, path.toString(), missingCode = "FILE_CHANGED") {
                    Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { }
                    scan.safeAttributes(path)
                }
            }
        }
        val errors = scan.errors.toMutableList()
        if (found.size > 1) {
            val namingCollision = found.groupBy { it.parentFile }.any { (_, files) -> files.size > 1 }
            if (namingCollision) errors += "AMBIGUOUS_DATA_NAMES: __data and __data__ coexist"
            if (found.map { it.parentFile }.distinct().size > 1) {
                errors += "AMBIGUOUS_CACHE_ROOTS: the same bundle/hash exists in multiple roots"
            }
        }
        if (found.isEmpty() && errors.isEmpty()) errors += "NOT_FOUND: $relativePath under $root"
        return TargetCandidates(found.sortedBy { it.path }, errors.distinct())
    }

    private data class Settings(
        val expectedNames: MutableSet<String> = linkedSetOf(),
        val warnings: MutableList<String> = mutableListOf(),
        var complete: Boolean = true,
    )

    private fun readSettings(scan: Scan): Settings {
        val result = Settings()
        var readAny = false
        if (apkPaths.size > MAX_APKS) {
            result.complete = false
            result.warnings += "LIMIT_APKS: More than $MAX_APKS APK paths"
        }
        for (apkPath in apkPaths.take(MAX_APKS).distinct()) {
            val success = scan.attempt(result.warnings, apkPath) {
                scan.checkTime()
                val path = File(apkPath).toPath()
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                    throw Problem("UNSAFE_APK", "APK is not a regular file")
                }
                checkZipDirectory(path, scan)
                ZipFile(apkPath).use { zip ->
                    if (zip.size() > MAX_ZIP_ENTRIES) throw Problem("LIMIT_ZIP_ENTRIES", apkPath)
                    // Also reject duplicate settings entries rather than letting ZipFile choose one.
                    val entries = zip.entries()
                    var settingsCount = 0
                    while (entries.hasMoreElements()) {
                        scan.checkTime()
                        if (entries.nextElement().name == SETTINGS_ENTRY) settingsCount++
                    }
                    if (settingsCount > 1) throw Problem("AMBIGUOUS_APK_SETTINGS", apkPath)
                    val entry = zip.getEntry(SETTINGS_ENTRY) ?: return@use
                    if (entry.size > MAX_SETTINGS_BYTES) throw Problem("LIMIT_SETTINGS_BYTES", apkPath)
                    val raw = zip.getInputStream(entry).use { scan.readStream(it, MAX_SETTINGS_BYTES) }
                    val json = parseJson(raw, scan)
                    readAny = true
                    val locations = json.optJSONArray("m_CatalogLocations")
                    if (locations != null) {
                        if (locations.length() > MAX_RECORDS) throw Problem("LIMIT_RECORDS", "Catalog locations")
                        for (i in 0 until locations.length()) {
                            scan.checkTime()
                            val location = locations.getJSONObject(i)
                            val keys = location.optJSONArray("m_Keys") ?: continue
                            if ((0 until keys.length()).none {
                                    keys.optString(it) == "AddressablesMainContentCatalogCacheHash"
                                }) continue
                            val id = location.getString("m_InternalId")
                            val name = id.substringAfterLast('/')
                            if (!CATALOG_NAME.matches(name) || !name.endsWith(".hash")) {
                                throw Problem("UNSUPPORTED_CATALOG_PATH", id)
                            }
                            result.expectedNames += name
                            inspectSettingPath(id, "CATALOG_PATH", result.warnings)
                        }
                    }
                    if (json.has("m_CacheDirectoryOverride")) {
                        inspectSettingPath(json.getString("m_CacheDirectoryOverride"), "CACHE_OVERRIDE", result.warnings)
                    }
                    val initialization = json.optJSONArray("m_ExtraInitializationData")
                    if (initialization != null) {
                        if (initialization.length() > MAX_RECORDS) throw Problem("LIMIT_RECORDS", "Initialization data")
                        for (i in 0 until initialization.length()) {
                            scan.checkTime()
                            val item = initialization.getJSONObject(i)
                            if (item.optString("m_Id") != "CacheInitialization") continue
                            val data = item.opt("m_Data")
                            val cache = when (data) {
                                is JSONObject -> data
                                is String -> parseJson(data, scan)
                                else -> throw Problem("INVALID_CACHE_SETTINGS", "Missing cache initialization data")
                            }
                            inspectSettingPath(cache.optString("m_CacheDirectoryOverride"), "CACHE_OVERRIDE", result.warnings)
                        }
                    }
                    scan.checkTime()
                }
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (after.isSymbolicLink || attributes.fileKey() != after.fileKey() ||
                    attributes.size() != after.size() || attributes.lastModifiedTime() != after.lastModifiedTime()
                ) throw Problem("APK_CHANGED", "APK changed during inspection")
                true
            }
            if (success != true) result.complete = false
        }
        if (apkPaths.isNotEmpty() && !readAny) {
            result.complete = false
            result.warnings += "APK_SETTINGS_MISSING: $SETTINGS_ENTRY was not readable in any APK"
        }
        return result
    }

    /** Bound the central directory before ZipFile allocates its index. ZIP64 APKs are diagnostic-only. */
    private fun checkZipDirectory(path: Path, scan: Scan) {
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size < 22) throw Problem("INVALID_APK_ZIP", "Missing ZIP end record")
            val tail = ByteBuffer.allocate(minOf(size, 65_557L).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            channel.position(size - tail.capacity())
            while (tail.hasRemaining()) {
                scan.checkTime()
                if (channel.read(tail) < 0) throw Problem("APK_CHANGED", "Truncated ZIP end record")
            }
            for (i in tail.capacity() - 22 downTo 0) {
                if (tail.getInt(i) != 0x06054b50 ||
                    i + 22 + (tail.getShort(i + 20).toInt() and 65535) != tail.capacity()
                ) continue
                val count = tail.getShort(i + 10).toInt() and 65535
                val bytes = tail.getInt(i + 12).toLong() and 0xffffffffL
                val offset = tail.getInt(i + 16).toLong() and 0xffffffffL
                if (count == 65535 || bytes == 0xffffffffL || offset == 0xffffffffL) {
                    throw Problem("UNSUPPORTED_ZIP64", "ZIP64 settings inspection is not supported")
                }
                if (bytes > MAX_ZIP_DIRECTORY_BYTES) throw Problem("LIMIT_ZIP_DIRECTORY_BYTES", "ZIP directory exceeds 32 MiB")
                if (tail.getShort(i + 4).toInt() != 0 || tail.getShort(i + 6).toInt() != 0 ||
                    (tail.getShort(i + 8).toInt() and 65535) != count ||
                    offset + bytes > size - tail.capacity() + i
                ) throw Problem("INVALID_APK_ZIP", "Invalid or multipart ZIP directory")
                return
            }
            throw Problem("INVALID_APK_ZIP", "Missing ZIP end record")
        }
    }

    private fun inspectSettingPath(raw: String, label: String, warnings: MutableList<String>) {
        if (raw.isBlank()) return
        val resolved = if (raw == PERSISTENT || raw.startsWith("$PERSISTENT/")) {
            root.toString() + raw.removePrefix(PERSISTENT)
        } else raw
        if ('{' in resolved || '}' in resolved) {
            warnings += "UNRESOLVED_$label: $raw; dynamic properties are not evaluated"
            return
        }
        val value = File(resolved).toPath()
        val target = (if (value.isAbsolute) value else root.resolve(value)).normalize()
        if (!target.startsWith(root) || target.any { it.toString() == MANAGER } ||
            raw.split('/').any { it == ".." } || ('\\' in raw && File.separatorChar != '\\')
        ) {
            warnings += "UNSAFE_$label: $raw; outside the package files root or excluded"
        } else {
            warnings += "APK_$label: $raw -> $target; location hint only, all in-scope duplicates are retained"
        }
    }

    /** Partial Addressables table inspection; neither JSON MD5 nor m_BuildResultHash is a catalog identity check. */
    private fun inspectBundleIds(json: JSONObject, bundles: MutableList<CatalogBundle>, scan: Scan) {
        val ids = json.optJSONArray("m_InternalIds")
            ?: throw Problem("CATALOG_BUNDLE_IDS_UNAVAILABLE", "JSON has no Addressables internal IDs")
        if (ids.length() > MAX_RECORDS) throw Problem("LIMIT_RECORDS", "Internal IDs")
        for (i in 0 until ids.length()) {
            scan.checkTime()
            ids.getString(i)
        }
        if (!json.has("m_EntryDataString") || !json.has("m_ExtraDataString")) {
            throw Problem("CATALOG_BUNDLE_IDS_UNAVAILABLE", "Serialized bundle option tables are absent")
        }
        val entries = decodeTable(json.getString("m_EntryDataString"))
        val extras = decodeTable(json.getString("m_ExtraDataString"))
        val providers = json.getJSONArray("m_ProviderIds")
        val types = json.getJSONArray("m_resourceTypes")
        if (providers.length() > MAX_RECORDS || types.length() > MAX_RECORDS) {
            throw Problem("LIMIT_RECORDS", "Provider/type tables")
        }
        if (entries.remaining() < 4) throw Problem("INVALID_CATALOG_TABLE", "Missing entry count")
        val count = entries.int
        if (count < 0 || count > MAX_RECORDS || entries.remaining().toLong() != count.toLong() * 28) {
            throw Problem("INVALID_CATALOG_TABLE", "Invalid entry count/range")
        }
        for (i in 0 until count) {
            scan.checkTime()
            val idIndex = entries.int
            val providerIndex = entries.int
            entries.int // dependency key index: not decoded by this diagnostic reader
            entries.int // dependency hash
            val dataIndex = entries.int
            entries.int // primary key index
            val typeIndex = entries.int
            if (idIndex !in 0 until ids.length() || providerIndex !in 0 until providers.length() ||
                typeIndex !in 0 until types.length() || dataIndex < -1 || dataIndex >= extras.limit()
            ) throw Problem("INVALID_CATALOG_TABLE", "Entry $i references an invalid table index")
            providers.getString(providerIndex)
            val type = types.getJSONObject(typeIndex).getString("m_ClassName")
            if (type != ASSET_BUNDLE_TYPE || dataIndex == -1) continue
            val payload = extras.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            payload.position(dataIndex)
            if (payload.remaining() < 1 || payload.get().toInt() != 7) {
                throw Problem("UNSUPPORTED_BUNDLE_OPTIONS", "Entry $i is not a serialized JSON object")
            }
            readShortString(payload) // assembly name is data, never loaded
            val className = readShortString(payload)
            if (!className.endsWith(".AssetBundleRequestOptions")) continue
            if (payload.remaining() < 4) throw Problem("INVALID_CATALOG_TABLE", "Truncated JSON length")
            val length = payload.int
            if (length < 0 || length > MAX_SETTINGS_BYTES || length % 2 != 0 || length > payload.remaining()) {
                throw Problem("INVALID_CATALOG_TABLE", "Bundle option JSON exceeds table bounds")
            }
            val bytes = ByteArray(length)
            payload.get(bytes)
            val options = parseJson(Charsets.UTF_16LE.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), scan)
            val name = options.getString("m_BundleName")
            val hash = options.getString("m_Hash")
            if (!SAFE_NAME.matches(name) || !HASH.matches(hash)) {
                throw Problem("INVALID_BUNDLE_ID", "Entry $i has an unsupported bundle name/hash")
            }
            bundles += CatalogBundle(name, hash.lowercase(), ids.getString(idIndex))
        }
    }

    private fun decodeTable(value: String): ByteBuffer {
        if (value.length > MAX_CATALOG_BYTES) throw Problem("LIMIT_TABLE_BYTES", "Encoded table")
        return ByteBuffer.wrap(Base64.getDecoder().decode(value)).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun readShortString(buffer: ByteBuffer): String {
        if (buffer.remaining() < 1) throw Problem("INVALID_CATALOG_TABLE", "Truncated type name")
        val length = buffer.get().toInt() and 255
        if (length > buffer.remaining()) throw Problem("INVALID_CATALOG_TABLE", "Type name outside table")
        val value = ByteArray(length)
        buffer.get(value)
        return String(value, Charsets.US_ASCII)
    }

    private fun parseJson(raw: String, scan: Scan): JSONObject {
        // org.json versions have different nesting limits. Bound nesting before invoking either JVM or Android JSON.
        var depth = 0
        var quoted = false
        var escaped = false
        raw.forEachIndexed { index, char ->
            if (index % 4096 == 0) scan.checkTime()
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > MAX_JSON_DEPTH) throw Problem("LIMIT_JSON_DEPTH", "Nested JSON")
                '}', ']' -> depth--
            }
        }
        val tokener = JSONTokener(raw.removePrefix("\uFEFF"))
        val value = tokener.nextValue()
        if (value !is JSONObject || tokener.nextClean() != '\u0000') {
            throw Problem("INVALID_JSON", "Expected one JSON object")
        }
        scan.checkTime()
        return value
    }

    private class Problem(val code: String, message: String) : IOException(message)

    private inner class Scan {
        val errors = mutableListOf<String>()
        private val started = nanoTime()
        private var entries = 0
        private var readBytes = 0L
        private var canonicalRoot: Path? = null

        fun checkTime() {
            if (nanoTime() - started >= limits.maxMillis * 1_000_000) {
                throw Problem("LIMIT_TIME", "Search exceeded ${limits.maxMillis} ms; observation is incomplete")
            }
        }

        fun safeAttributes(path: Path): BasicFileAttributes {
            checkTime()
            if (!path.startsWith(root)) throw Problem("UNSAFE_PATH", "$path escapes $root")
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attrs.isSymbolicLink) throw Problem("SYMLINK_REJECTED", path.toString())
            val canonical = path.toRealPath()
            val base = canonicalRoot ?: root.toRealPath().also { canonicalRoot = it }
            if (!canonical.startsWith(base) || canonical != base.resolve(root.relativize(path))) {
                throw Problem("CANONICAL_ESCAPE", "$path resolves to $canonical")
            }
            return attrs
        }

        fun walk(onFile: (Path) -> Unit) {
            val attributes = attempt(errors, root.toString()) { safeAttributes(root) } ?: return
            if (!attributes.isDirectory) {
                errors += "NOT_DIRECTORY: $root"
                return
            }
            attempt(errors, root.toString(), missingCode = "TRAVERSAL_CHANGED") {
                visit(root, 0, onFile)
            }
        }

        private fun visit(directory: Path, depth: Int, onFile: (Path) -> Unit) {
            checkTime()
            Files.newDirectoryStream(directory).use { stream ->
                for (path in stream) {
                    checkTime()
                    if (++entries > limits.maxEntries) {
                        throw Problem("LIMIT_ENTRIES", "Search exceeded ${limits.maxEntries} entries; observation is incomplete")
                    }
                    if (path.fileName.toString() == MANAGER) continue
                    try {
                        val attrs = safeAttributes(path)
                        if (attrs.isDirectory) {
                            if (depth >= limits.maxDepth) {
                                errors += "LIMIT_DEPTH: $path exceeds depth ${limits.maxDepth}; observation is incomplete"
                            } else visit(path, depth + 1, onFile)
                        } else if (attrs.isRegularFile) onFile(path)
                    } catch (error: Exception) {
                        if (error is Problem && (error.code == "LIMIT_TIME" || error.code == "LIMIT_ENTRIES" ||
                                error.code == "LIMIT_CATALOGS")) throw error
                        errors += diagnostic(error, path.toString(), missingCode = "TRAVERSAL_CHANGED")
                    }
                }
            }
            // Detect directory replacement while enumerating it, including junction/symlink substitution.
            safeAttributes(directory)
        }

        fun read(path: Path, maxBytes: Int): String {
            val before = safeAttributes(path)
            if (!before.isRegularFile) throw Problem("NOT_REGULAR_FILE", path.toString())
            if (before.size() > maxBytes) throw Problem("LIMIT_FILE_BYTES", "$path exceeds $maxBytes bytes")
            val value = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { readStream(it, maxBytes) }
            val after = safeAttributes(path)
            if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
                before.fileKey() != after.fileKey()
            ) throw Problem("FILE_CHANGED", "$path changed during inspection")
            return value
        }

        fun readStream(input: InputStream, maxBytes: Int): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                checkTime()
                val count = input.read(buffer)
                if (count < 0) break
                readBytes += count
                if (readBytes > limits.maxReadBytes) throw Problem("LIMIT_READ_BYTES", "Total read budget exceeded")
                if (output.size().toLong() + count > maxBytes) throw Problem("LIMIT_FILE_BYTES", "Read exceeds $maxBytes bytes")
                output.write(buffer, 0, count)
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString()
        }

        fun <T> attempt(
            destination: MutableList<String>,
            path: String,
            missingCode: String = "NOT_FOUND",
            action: () -> T,
        ): T? = try {
            action()
        } catch (error: Exception) {
            destination += diagnostic(error, path, missingCode)
            null
        }
    }

    private fun diagnostic(error: Exception, path: String, missingCode: String = "NOT_FOUND"): String {
        val actual = if (error is DirectoryIteratorException) error.cause ?: error else error
        val code = when (actual) {
            is Problem -> actual.code
            is AccessDeniedException, is SecurityException -> "ACCESS_DENIED"
            is NoSuchFileException -> missingCode
            is org.json.JSONException -> "INVALID_JSON"
            else -> "IO_OR_FORMAT_ERROR"
        }
        return "$code: $path: ${actual.message.orEmpty().take(300)}"
    }

    private companion object {
        const val MANAGER = ".astralpatch-manager"
        const val PERSISTENT = "{UnityEngine.Application.persistentDataPath}"
        const val SETTINGS_ENTRY = "assets/aa/settings.json"
        const val ASSET_BUNDLE_TYPE = "UnityEngine.ResourceManagement.ResourceProviders.IAssetBundleResource"
        const val MAX_HASH_BYTES = 1024
        const val MAX_SETTINGS_BYTES = 1024 * 1024
        const val MAX_CATALOG_BYTES = 32 * 1024 * 1024
        const val MAX_JSON_DEPTH = 64
        const val MAX_RECORDS = 100_000
        const val MAX_APKS = 64
        const val MAX_ZIP_ENTRIES = 100_000
        const val MAX_ZIP_DIRECTORY_BYTES = 32 * 1024 * 1024
        val DATA_NAMES = setOf("__data", "__data__")
        val HASH = Regex("[0-9a-fA-F]{32}")
        val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}")
        val CATALOG_NAME = Regex("catalog_([0-9]+(?:\\.[0-9]+){0,7})\\.(hash|json|bundle)")
    }
}
