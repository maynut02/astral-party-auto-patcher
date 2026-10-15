//! Resolve published Unity cache identities against the installed Addressables catalog.
//!
//! The binary tables follow builder/src/astral_builder/addressables/catalog.py:
//! locations are seven little-endian i32s, and extra-data object 7 contains an
//! ASCII assembly/class name followed by a byte-counted UTF-16LE JSON payload.

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::Deserialize;
use thiserror::Error;

use crate::protocol::{InstallTarget, PatchManifest};

const BUNDLE_RESOURCE: &str =
    "UnityEngine.ResourceManagement.ResourceProviders.IAssetBundleResource";
const RUNTIME_PATH: &str = "{UnityEngine.AddressableAssets.Addressables.RuntimePath}/";
const REMOTE_PATH: &str = "{App.WebServerConfig.Path}/";

#[derive(Debug, Error)]
pub enum CatalogError {
    #[error(
        "an Addressables catalog is required to resolve patch bundle locations; launch the game first to obtain its catalog, then retry"
    )]
    MissingCatalog,
    #[error(
        "could not read Addressables catalog {path:?}: {source}; launch the game first to obtain its catalog, then retry"
    )]
    ReadCatalog {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },
    #[error("invalid Addressables catalog: {0}")]
    InvalidCatalog(String),
    #[error("Addressables manifest path must be a safe bundleName/hash/__data path: {0:?}")]
    InvalidCachePath(String),
    #[error(
        "no catalog AssetBundleRequestOptions record matches the exact bundle name and hash in {0:?}"
    )]
    MissingBundle(String),
    #[error(
        "patch-created bundle {0:?} has no matching catalog record; its destination cannot be inferred safely from the cache path"
    )]
    MissingCreatedBundle(String),
    #[error("conflicting catalog records for {path:?}: {internal_ids:?}")]
    AmbiguousBundle {
        path: String,
        internal_ids: Vec<String>,
    },
    #[error("unsupported Addressables internal ID for {path:?}: {internal_id:?}")]
    UnsupportedInternalId { path: String, internal_id: String },
    #[error("unsafe Addressables RuntimePath: {0:?}")]
    UnsafeRuntimePath(String),
    #[error("resolving the manifest makes multiple files target {target:?}/{path}")]
    ConflictingDestination { target: InstallTarget, path: String },
}

/// Clone a release manifest, changing only Addressables targets backed by a
/// RuntimePath record to GameData `StreamingAssets/aa/<RuntimePath tail>`.
/// Remote records retain every field, including their cache path and operation.
/// Matching uses both AssetBundleRequestOptions fields, never bundle filenames.
///
/// With no Addressables files, no catalog is read. Otherwise a readable, valid
/// catalog and an unambiguous exact record are required, including for `create`
/// files: a cache identity alone cannot establish a new bundle's origin.
/// This function never probes or writes game files and leaves its input intact.
pub fn resolve_manifest(
    manifest: &PatchManifest,
    catalog_path: Option<&Path>,
) -> Result<PatchManifest, CatalogError> {
    if !manifest
        .files
        .iter()
        .any(|file| file.target == InstallTarget::Addressables)
    {
        return Ok(manifest.clone());
    }
    let path = catalog_path.ok_or(CatalogError::MissingCatalog)?;
    let raw = fs::read(path).map_err(|source| CatalogError::ReadCatalog {
        path: path.to_owned(),
        source,
    })?;
    let bundles = decode_catalog(&raw)?;
    let mut resolved = manifest.clone();
    for file in &mut resolved.files {
        if file.target != InstallTarget::Addressables {
            continue;
        }
        let cache_path = file.path.replace('\\', "/");
        let parts: Vec<_> = cache_path.split('/').collect();
        if parts.len() != 3 || parts[2] != "__data" || !safe_relative_path(&cache_path) {
            return Err(CatalogError::InvalidCachePath(file.path.clone()));
        }
        let key = (parts[0].to_owned(), parts[1].to_owned());
        let records = bundles.get(&key).ok_or_else(|| {
            if file.operation == "create" {
                CatalogError::MissingCreatedBundle(file.path.clone())
            } else {
                CatalogError::MissingBundle(file.path.clone())
            }
        })?;
        let mut destination = None;
        for internal_id in records {
            let candidate = resolve_internal_id(&file.path, internal_id)?;
            if destination
                .as_ref()
                .is_some_and(|prior| prior != &candidate)
            {
                return Err(CatalogError::AmbiguousBundle {
                    path: file.path.clone(),
                    internal_ids: records.clone(),
                });
            }
            destination = Some(candidate);
        }
        if let Some(Destination::Runtime(path)) = destination {
            file.target = InstallTarget::GameData;
            file.path = path;
        }
    }
    // Distinct cache identities must not collapse onto one Windows file, nor
    // collide with an existing game-data entry after resolution.
    let mut destinations = std::collections::HashSet::new();
    for file in &resolved.files {
        let key = (
            file.target,
            file.path.replace('\\', "/").to_ascii_lowercase(),
        );
        if !destinations.insert(key) {
            return Err(CatalogError::ConflictingDestination {
                target: file.target,
                path: file.path.clone(),
            });
        }
    }
    Ok(resolved)
}

#[derive(Debug, PartialEq, Eq)]
enum Destination {
    Runtime(String),
    Remote(String),
}

fn resolve_internal_id(path: &str, internal_id: &str) -> Result<Destination, CatalogError> {
    let normalized = internal_id.replace('\\', "/");
    if let Some(tail) = normalized.strip_prefix(RUNTIME_PATH) {
        if !safe_relative_path(tail) {
            return Err(CatalogError::UnsafeRuntimePath(internal_id.to_owned()));
        }
        return Ok(Destination::Runtime(format!("StreamingAssets/aa/{tail}")));
    }
    // These IDs remain Unity cache entries, including catalogs containing an
    // already expanded CDN URL. No URL or runtime variable is evaluated here.
    let remote_tail = normalized
        .strip_prefix(REMOTE_PATH)
        .or_else(|| normalized.strip_prefix("https://"))
        .or_else(|| normalized.strip_prefix("http://"));
    if remote_tail.is_some_and(|tail| !tail.is_empty() && !tail.starts_with('/')) {
        return Ok(Destination::Remote(normalized));
    }
    Err(CatalogError::UnsupportedInternalId {
        path: path.to_owned(),
        internal_id: internal_id.to_owned(),
    })
}

// Validate text before Path normalizes away dot/repeated components. Apply
// Windows rules even when these tests run on another platform; in particular,
// reject drive/ADS syntax, UNC roots, device names, and encoded traversal.
fn safe_relative_path(path: &str) -> bool {
    !path.is_empty()
        && path.split('/').all(|part| {
            if part.is_empty()
                || part.ends_with(['.', ' '])
                || part
                    .chars()
                    .any(|c| c.is_control() || "\\:<>\"|?*%{}".contains(c))
            {
                return false;
            }
            let stem = part
                .split('.')
                .next()
                .unwrap_or("")
                .trim_end_matches(' ')
                .to_ascii_uppercase();
            !matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
                && !["COM", "LPT"].iter().any(|prefix| {
                    stem.strip_prefix(prefix).is_some_and(|n| {
                        matches!(
                            n,
                            "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "¹" | "²" | "³"
                        )
                    })
                })
        })
}

fn invalid(detail: impl Into<String>) -> CatalogError {
    CatalogError::InvalidCatalog(detail.into())
}

#[derive(Deserialize)]
struct EncodedCatalog {
    #[serde(rename = "m_InternalIds")]
    internal_ids: Vec<String>,
    #[serde(rename = "m_ProviderIds")]
    provider_ids: Vec<String>,
    #[serde(rename = "m_resourceTypes")]
    resource_types: Vec<ResourceType>,
    #[serde(rename = "m_BucketDataString")]
    bucket_data: String,
    #[serde(rename = "m_KeyDataString")]
    key_data: String,
    #[serde(rename = "m_EntryDataString")]
    entry_data: String,
    #[serde(rename = "m_ExtraDataString", default)]
    extra_data: String,
}

#[derive(Deserialize)]
struct ResourceType {
    #[serde(rename = "m_ClassName")]
    class_name: String,
}

#[derive(Deserialize)]
struct BundleOptions {
    #[serde(rename = "m_BundleName")]
    name: String,
    #[serde(rename = "m_Hash")]
    hash: String,
    #[serde(rename = "m_BundleSize")]
    _size: u64,
    #[serde(rename = "m_Crc")]
    _crc: u32,
}

type BundleIndex = HashMap<(String, String), Vec<String>>;

fn decode_catalog(raw: &[u8]) -> Result<BundleIndex, CatalogError> {
    let raw = raw.strip_prefix(b"\xef\xbb\xbf").unwrap_or(raw);
    let catalog: EncodedCatalog =
        serde_json::from_slice(raw).map_err(|error| invalid(format!("catalog JSON: {error}")))?;
    let decode = |name, value| {
        STANDARD
            .decode(value)
            .map_err(|error| invalid(format!("{name} is not valid base64: {error}")))
    };
    let bucket_data = decode("m_BucketDataString", &catalog.bucket_data)?;
    let key_data = decode("m_KeyDataString", &catalog.key_data)?;
    let entry_data = decode("m_EntryDataString", &catalog.entry_data)?;
    let extra_data = decode("m_ExtraDataString", &catalog.extra_data)?;
    let mut buckets = Reader::new(&bucket_data, "bucket data");
    let bucket_count = buckets.nonnegative("bucket count")?;
    if bucket_count > buckets.remaining() / 8 {
        return Err(invalid("bucket count exceeds bucket data bounds"));
    }
    let mut key_header = Reader::new(&key_data, "key data");
    let key_count = key_header.nonnegative("key count")?;
    if key_count != bucket_count {
        return Err(invalid("key count does not match bucket count"));
    }
    let mut entries = Reader::new(&entry_data, "entry data");
    let entry_count = entries.nonnegative("entry count")?;
    if entry_count.checked_mul(28) != Some(entries.remaining()) {
        return Err(invalid(
            "entry data length does not match the seven-int32 record format",
        ));
    }
    for _ in 0..bucket_count {
        let key_offset = buckets.nonnegative("key offset")?;
        if key_offset < 4 {
            return Err(invalid("key offset overlaps the key count header"));
        }
        read_object(&key_data, key_offset, "key data")?;
        let count = buckets.nonnegative("bucket entry count")?;
        if count > buckets.remaining() / 4 {
            return Err(invalid("bucket entry count exceeds bucket data bounds"));
        }
        for _ in 0..count {
            table_index(buckets.i32()?, entry_count, "bucket entry")?;
        }
    }
    if buckets.remaining() != 0 {
        return Err(invalid("unexpected trailing bucket data"));
    }
    let mut result: BundleIndex = HashMap::new();
    for entry in 0..entry_count {
        let id = table_index(entries.i32()?, catalog.internal_ids.len(), "internal ID")?;
        table_index(entries.i32()?, catalog.provider_ids.len(), "provider ID")?;
        optional_index(entries.i32()?, key_count, "dependency key")?;
        let _dependency_hash = entries.i32()?;
        let data_offset = optional_index(entries.i32()?, extra_data.len(), "extra data")?;
        table_index(entries.i32()?, key_count, "primary key")?;
        let resource_type = table_index(
            entries.i32()?,
            catalog.resource_types.len(),
            "resource type",
        )?;
        let data = match data_offset {
            Some(offset) => read_object(&extra_data, offset, "extra data")?,
            None => None,
        };
        if catalog.resource_types[resource_type].class_name != BUNDLE_RESOURCE {
            continue;
        }
        let options = data
            .filter(|data| {
                data.class_name == "AssetBundleRequestOptions"
                    || data.class_name.ends_with(".AssetBundleRequestOptions")
            })
            .ok_or_else(|| {
                invalid(format!(
                    "bundle entry {entry} has no AssetBundleRequestOptions"
                ))
            })?;
        let options: BundleOptions = serde_json::from_str(&options.json_text).map_err(|error| {
            invalid(format!("entry {entry} AssetBundleRequestOptions: {error}"))
        })?;
        if options.name.contains('/')
            || options.hash.contains('/')
            || !safe_relative_path(&options.name)
            || !safe_relative_path(&options.hash)
        {
            return Err(invalid(format!(
                "entry {entry} has an invalid bundle name/hash"
            )));
        }
        result
            .entry((options.name, options.hash))
            .or_default()
            .push(catalog.internal_ids[id].clone());
    }
    Ok(result)
}

fn table_index(value: i32, length: usize, table: &str) -> Result<usize, CatalogError> {
    usize::try_from(value)
        .ok()
        .filter(|&index| index < length)
        .ok_or_else(|| {
            invalid(format!(
                "invalid {table} index {value} (table length {length})"
            ))
        })
}

fn optional_index(value: i32, length: usize, table: &str) -> Result<Option<usize>, CatalogError> {
    if value == -1 {
        Ok(None)
    } else {
        table_index(value, length, table).map(Some)
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
    context: &'static str,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8], context: &'static str) -> Self {
        Self {
            bytes,
            offset: 0,
            context,
        }
    }

    fn remaining(&self) -> usize {
        self.bytes.len() - self.offset
    }

    fn take(&mut self, count: usize) -> Result<&'a [u8], CatalogError> {
        let end = self
            .offset
            .checked_add(count)
            .filter(|&end| end <= self.bytes.len())
            .ok_or_else(|| {
                invalid(format!(
                    "{} truncated at offset {} (need {count} bytes)",
                    self.context, self.offset
                ))
            })?;
        let bytes = &self.bytes[self.offset..end];
        self.offset = end;
        Ok(bytes)
    }

    fn byte(&mut self) -> Result<u8, CatalogError> {
        Ok(self.take(1)?[0])
    }

    fn i32(&mut self) -> Result<i32, CatalogError> {
        let b = self.take(4)?;
        Ok(i32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn nonnegative(&mut self, field: &str) -> Result<usize, CatalogError> {
        usize::try_from(self.i32()?)
            .map_err(|_| invalid(format!("negative {field} in {}", self.context)))
    }

    fn ascii(&mut self, count: usize) -> Result<String, CatalogError> {
        let bytes = self.take(count)?;
        if !bytes.is_ascii() {
            return Err(invalid(format!("non-ASCII string in {}", self.context)));
        }
        // ASCII validation above also establishes valid UTF-8.
        String::from_utf8(bytes.to_vec()).map_err(|error| invalid(error.to_string()))
    }

    fn utf16(&mut self, count: usize) -> Result<String, CatalogError> {
        let bytes = self.take(count)?;
        if !count.is_multiple_of(2) {
            return Err(invalid(format!(
                "odd UTF-16LE byte length in {}",
                self.context
            )));
        }
        let units: Vec<_> = bytes
            .chunks_exact(2)
            .map(|b| u16::from_le_bytes([b[0], b[1]]))
            .collect();
        String::from_utf16(&units)
            .map_err(|error| invalid(format!("invalid UTF-16LE in {}: {error}", self.context)))
    }
}

struct JsonObject {
    class_name: String,
    json_text: String,
}

fn read_object(
    bytes: &[u8],
    offset: usize,
    context: &'static str,
) -> Result<Option<JsonObject>, CatalogError> {
    let slice = bytes
        .get(offset..)
        .ok_or_else(|| invalid(format!("{context} object offset {offset} is out of bounds")))?;
    let mut reader = Reader::new(slice, context);
    match reader.byte()? {
        0 | 1 => {
            let kind = slice[0];
            let count = reader.nonnegative("serialized string length")?;
            if kind == 0 {
                reader.ascii(count)?;
            } else {
                reader.utf16(count)?;
            }
        }
        2 => {
            reader.take(2)?;
        }
        3 | 4 => {
            reader.take(4)?;
        }
        5 | 6 => {
            let count = usize::from(reader.byte()?);
            reader.ascii(count)?;
        }
        7 => {
            let count = usize::from(reader.byte()?);
            reader.ascii(count)?; // assembly name; never instantiate a .NET type
            let count = usize::from(reader.byte()?);
            let class_name = reader.ascii(count)?;
            let count = reader.nonnegative("JsonObject payload length")?;
            let json_text = reader.utf16(count)?;
            return Ok(Some(JsonObject {
                class_name,
                json_text,
            }));
        }
        kind => {
            return Err(invalid(format!(
                "unsupported serialized object type {kind} in {context}"
            )));
        }
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{ManifestFile, PatchMetadata, TargetGame};
    use serde_json::{Value, json};

    fn put_i32(bytes: &mut Vec<u8>, value: i32) {
        bytes.extend(value.to_le_bytes());
    }

    fn object(json: &str) -> Vec<u8> {
        let mut result = vec![7];
        for name in [
            "Unity.ResourceManager",
            "UnityEngine.ResourceManagement.ResourceProviders.AssetBundleRequestOptions",
        ] {
            result.push(name.len() as u8);
            result.extend(name.as_bytes());
        }
        let payload: Vec<_> = json.encode_utf16().flat_map(u16::to_le_bytes).collect();
        put_i32(&mut result, payload.len() as i32);
        result.extend(payload);
        result
    }

    fn fixture(records: &[(&str, &str, &str)]) -> Value {
        let mut keys = Vec::new();
        let mut buckets = Vec::new();
        let mut entries = Vec::new();
        let mut extra = Vec::new();
        for bytes in [&mut keys, &mut buckets, &mut entries] {
            put_i32(bytes, records.len() as i32);
        }
        for (i, &(name, hash, _)) in records.iter().enumerate() {
            put_i32(&mut buckets, keys.len() as i32);
            put_i32(&mut buckets, 1);
            put_i32(&mut buckets, i as i32);
            let key = format!("unrelated-primary-key-{i}");
            keys.push(0);
            put_i32(&mut keys, key.len() as i32);
            keys.extend(key.as_bytes());
            for field in [i as i32, 0, -1, i32::MIN, extra.len() as i32, i as i32, 0] {
                put_i32(&mut entries, field);
            }
            extra.extend(object(
                &json!({
                    "m_BundleName": name, "m_Hash": hash, "m_BundleSize": 31618, "m_Crc": 0,
                    "unusedUnicode": "한국어 🦀"
                })
                .to_string(),
            ));
        }
        json!({
            "m_InternalIds": records.iter().map(|record| record.2).collect::<Vec<_>>(),
            "m_ProviderIds": ["UnityEngine.ResourceManagement.ResourceProviders.AssetBundleProvider"],
            "m_resourceTypes": [{"m_AssemblyName": "Unity.ResourceManager", "m_ClassName": BUNDLE_RESOURCE}],
            "m_BucketDataString": STANDARD.encode(buckets), "m_KeyDataString": STANDARD.encode(keys),
            "m_EntryDataString": STANDARD.encode(entries), "m_ExtraDataString": STANDARD.encode(extra),
            "m_InternalIdPrefixes": []
        })
    }

    fn manifest(path: &str) -> PatchManifest {
        PatchManifest {
            schema_version: 2,
            patch: PatchMetadata {
                version: "v3.3.0_r012_p0".into(),
                channel: "release".into(),
                route: "INT_STEAM".into(),
                build_id: "build".into(),
                translation_fingerprint: "a".repeat(64),
            },
            game: TargetGame {
                version: "3.3.0".into(),
                revision: "012".into(),
                catalog_hash: "b".repeat(32),
            },
            files: vec![ManifestFile {
                target: InstallTarget::Addressables,
                path: path.into(),
                operation: "replace".into(),
                download_url: "https://example.test/patched.gz".into(),
                download_sha256: "c".repeat(64),
                download_size: 10,
                compression: "gzip".into(),
                sha256: "d".repeat(64),
                size: 20,
                source_download_url: Some("https://example.test/source.gz".into()),
                source_download_sha256: Some("e".repeat(64)),
                source_download_size: Some(12),
                source_sha256: Some("f".repeat(64)),
                source_size: Some(24),
            }],
        }
    }

    fn resolve(input: &PatchManifest, catalog: &Value) -> Result<PatchManifest, CatalogError> {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("catalog.json");
        fs::write(&path, serde_json::to_vec(catalog).unwrap()).unwrap();
        resolve_manifest(input, Some(&path))
    }

    fn bytes(catalog: &Value, field: &str) -> Vec<u8> {
        STANDARD.decode(catalog[field].as_str().unwrap()).unwrap()
    }

    fn mutate_i32(catalog: &mut Value, field: &str, offset: usize, value: i32) {
        let mut data = bytes(catalog, field);
        data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        catalog[field] = json!(STANDARD.encode(data));
    }

    #[test]
    fn both_steam_routes_preserve_cdn_and_only_retarget_runtime_files() {
        for (route, font, separator) in [
            ("INT_STEAM", "bf9fdf4f4afafccd49732369c13d9c82", "\\"),
            ("CN_STEAM", "4cb3d6e4cad44b373eb65985652e9894", "/"),
        ] {
            let runtime = format!(
                "{{UnityEngine.AddressableAssets.Addressables.RuntimePath}}{separator}StandaloneWindows64{separator}{font}.bundle"
            );
            let remote = "{App.WebServerConfig.Path}\\localization.bundle";
            let catalog = fixture(&[("language", "langhash", remote), ("font", font, &runtime)]);
            let mut input = manifest("language/langhash/__data");
            input.patch.route = route.into();
            input
                .files
                .push(manifest(&format!("font/{font}/__data")).files.remove(0));
            let mut game_file = input.files[0].clone();
            game_file.target = InstallTarget::GameData;
            game_file.path = "data.unity3d".into();
            input.files.push(game_file);
            let original = input.clone();
            let mut expected = input.clone();
            expected.files[1].target = InstallTarget::GameData;
            expected.files[1].path =
                format!("StreamingAssets/aa/StandaloneWindows64/{font}.bundle");
            assert_eq!(resolve(&input, &catalog).unwrap(), expected);
            assert_eq!(input, original);
        }
    }

    #[test]
    fn already_expanded_http_cdn_urls_remain_cache_targets() {
        for remote in [
            "https://cdn.example.test/path/font.bundle",
            "http://cdn.example.test/font.bundle",
        ] {
            let input = manifest("font/hash/__data");
            assert_eq!(
                resolve(&input, &fixture(&[("font", "hash", remote)])).unwrap(),
                input
            );
        }
    }

    #[test]
    fn game_data_only_does_not_read_even_a_non_catalog_path() {
        let mut input = manifest("data.unity3d");
        input.files[0].target = InstallTarget::GameData;
        let temp = tempfile::tempdir().unwrap();
        assert_eq!(resolve_manifest(&input, None).unwrap(), input);
        assert_eq!(resolve_manifest(&input, Some(temp.path())).unwrap(), input);
        input.files.clear();
        assert_eq!(resolve_manifest(&input, None).unwrap(), input);
    }

    #[test]
    fn missing_or_unreadable_catalog_instructs_launching_game() {
        let input = manifest("font/hash/__data");
        let error = resolve_manifest(&input, None).unwrap_err();
        assert!(matches!(error, CatalogError::MissingCatalog));
        assert!(error.to_string().contains("launch the game first"));
        let temp = tempfile::tempdir().unwrap();
        let error = resolve_manifest(&input, Some(&temp.path().join("missing.json"))).unwrap_err();
        assert!(matches!(error, CatalogError::ReadCatalog { .. }));
        assert!(error.to_string().contains("launch the game first"));
    }

    #[test]
    fn matches_exact_case_sensitive_options_name_and_hash_not_filenames() {
        let runtime = "{UnityEngine.AddressableAssets.Addressables.RuntimePath}/actual/path.bundle";
        let catalog = fixture(&[
            ("font", "old", "{App.WebServerConfig.Path}/font.bundle"),
            ("font", "hash", runtime),
            ("other", "hash", "{App.WebServerConfig.Path}/other.bundle"),
        ]);
        let result = resolve(&manifest("font/hash/__data"), &catalog).unwrap();
        assert_eq!(
            result.files[0].path,
            "StreamingAssets/aa/actual/path.bundle"
        );
        for path in [
            "font/wrong/__data",
            "Font/hash/__data",
            "font/HASH/__data",
            "absent/hash/__data",
            "path/hash/__data",
        ] {
            assert!(
                matches!(
                    resolve(&manifest(path), &catalog),
                    Err(CatalogError::MissingBundle(_))
                ),
                "{path}"
            );
        }
    }

    #[test]
    fn create_entries_require_a_record_and_preserve_their_operation() {
        let mut input = manifest("font/hash/__data");
        input.files[0].operation = "create".into();
        let runtime = fixture(&[(
            "font",
            "hash",
            "{UnityEngine.AddressableAssets.Addressables.RuntimePath}/font.bundle",
        )]);
        let resolved = resolve(&input, &runtime).unwrap();
        assert_eq!(resolved.files[0].operation, "create");
        assert_eq!(resolved.files[0].target, InstallTarget::GameData);
        let remote = fixture(&[("font", "hash", "{App.WebServerConfig.Path}/font.bundle")]);
        assert_eq!(resolve(&input, &remote).unwrap(), input);
        assert!(matches!(
            resolve(&input, &fixture(&[])),
            Err(CatalogError::MissingCreatedBundle(_))
        ));
    }

    #[test]
    fn conflicting_exact_records_fail_but_identical_records_are_allowed() {
        let runtime = "{UnityEngine.AddressableAssets.Addressables.RuntimePath}/font.bundle";
        let remote = "{App.WebServerConfig.Path}/font.bundle";
        for other in [
            "{UnityEngine.AddressableAssets.Addressables.RuntimePath}/different.bundle",
            remote,
        ] {
            let catalog = fixture(&[("font", "hash", runtime), ("font", "hash", other)]);
            assert!(matches!(
                resolve(&manifest("font/hash/__data"), &catalog),
                Err(CatalogError::AmbiguousBundle { .. })
            ));
        }
        let catalog = fixture(&[
            ("font", "hash", remote),
            (
                "font",
                "hash",
                "{App.WebServerConfig.Path}/different.bundle",
            ),
        ]);
        assert!(matches!(
            resolve(&manifest("font/hash/__data"), &catalog),
            Err(CatalogError::AmbiguousBundle { .. })
        ));
        for id in [runtime, remote] {
            let catalog = fixture(&[("font", "hash", id), ("font", "hash", id)]);
            assert!(resolve(&manifest("font/hash/__data"), &catalog).is_ok());
        }
        let catalog = fixture(&[
            ("font", "hash", runtime),
            (
                "font",
                "hash",
                "{UnityEngine.AddressableAssets.Addressables.RuntimePath}\\font.bundle",
            ),
        ]);
        assert!(resolve(&manifest("font/hash/__data"), &catalog).is_ok());
    }

    #[test]
    fn runtime_destinations_cannot_collide_with_other_manifest_files() {
        let runtime = "{UnityEngine.AddressableAssets.Addressables.RuntimePath}/font.bundle";
        let catalog = fixture(&[("font", "hash", runtime), ("other", "hash", runtime)]);
        let mut input = manifest("font/hash/__data");
        input
            .files
            .push(manifest("other/hash/__data").files.remove(0));
        assert!(matches!(
            resolve(&input, &catalog),
            Err(CatalogError::ConflictingDestination { .. })
        ));
        input.files[1].target = InstallTarget::GameData;
        input.files[1].path = "streamingassets\\AA\\FONT.BUNDLE".into();
        assert!(matches!(
            resolve(&input, &catalog),
            Err(CatalogError::ConflictingDestination { .. })
        ));
    }

    #[test]
    fn rejects_unsafe_runtime_tails_on_every_platform() {
        for tail in [
            "",
            "/root.bundle",
            "//host/file",
            "../file",
            "./file",
            "a/../../file",
            "a\\..\\file",
            "C:/file",
            "C:file",
            "a//file",
            "a/",
            "a/file:stream",
            "%2e%2e/file",
            "a/CON.bundle",
            "a/lpt1.txt",
            "a/com¹.txt",
            "a/file.",
            "a/file ",
            "a/?.bundle",
            "a/{variable}",
            "a/\0.bundle",
            "a/\n.bundle",
        ] {
            let id = format!("{RUNTIME_PATH}{tail}");
            assert!(
                matches!(
                    resolve_internal_id("font/hash/__data", &id),
                    Err(CatalogError::UnsafeRuntimePath(_))
                ),
                "{id:?}"
            );
        }
        assert_eq!(
            resolve_internal_id(
                "font/hash/__data",
                &format!("{RUNTIME_PATH}한국어/font.bundle")
            )
            .unwrap(),
            Destination::Runtime("StreamingAssets/aa/한국어/font.bundle".into())
        );
    }

    #[test]
    fn rejects_unknown_schemes_and_runtime_lookalikes() {
        for id in [
            "file:///C:/font.bundle",
            "ftp://cdn/font.bundle",
            "C:\\font.bundle",
            "//host/font.bundle",
            "StandaloneWindows64/font.bundle",
            "{Unknown.Path}/font.bundle",
            "{UnityEngine.AddressableAssets.Addressables.RuntimePath}evil/font.bundle",
            "{UnityEngine.AddressableAssets.Addressables.RuntimePath}",
        ] {
            let catalog = fixture(&[("font", "hash", id)]);
            assert!(
                matches!(
                    resolve(&manifest("font/hash/__data"), &catalog),
                    Err(CatalogError::UnsupportedInternalId { .. })
                ),
                "{id}"
            );
        }
    }

    #[test]
    fn validates_cache_shape_before_matching_and_preserves_remote_spelling() {
        let catalog = fixture(&[("font", "hash", "{App.WebServerConfig.Path}/font.bundle")]);
        for path in [
            "font/hash",
            "font/hash/__data/extra",
            "/font/hash/__data",
            "../hash/__data",
            "font/../__data",
            "font//__data",
            "font/hash/not-data",
            "C:/hash/__data",
            "font/hash./__data",
        ] {
            assert!(
                matches!(
                    resolve(&manifest(path), &catalog),
                    Err(CatalogError::InvalidCachePath(_))
                ),
                "{path}"
            );
        }
        let input = manifest("font\\hash\\__data");
        assert_eq!(resolve(&input, &catalog).unwrap(), input);
    }

    #[test]
    fn accepts_utf8_bom_and_unicode_serialized_keys() {
        let mut catalog = fixture(&[("font", "hash", "{App.WebServerConfig.Path}/font.bundle")]);
        let mut key_data = 1_i32.to_le_bytes().to_vec();
        key_data.push(1);
        let key: Vec<_> = "한국어 🦀"
            .encode_utf16()
            .flat_map(u16::to_le_bytes)
            .collect();
        put_i32(&mut key_data, key.len() as i32);
        key_data.extend(key);
        catalog["m_KeyDataString"] = json!(STANDARD.encode(key_data));
        let mut raw = b"\xef\xbb\xbf".to_vec();
        raw.extend(serde_json::to_vec(&catalog).unwrap());
        assert!(decode_catalog(&raw).is_ok());
    }

    #[test]
    fn rejects_malformed_json_tables_and_base64() {
        for raw in [b"not JSON".as_slice(), b"null", b"[]", b"{}", b"\xff"] {
            assert!(matches!(
                decode_catalog(raw),
                Err(CatalogError::InvalidCatalog(_))
            ));
        }
        let base = fixture(&[("font", "hash", "{App.WebServerConfig.Path}/font.bundle")]);
        for field in [
            "m_BucketDataString",
            "m_KeyDataString",
            "m_EntryDataString",
            "m_ExtraDataString",
        ] {
            let mut catalog = base.clone();
            catalog[field] = json!("%%%invalid%%%");
            assert!(
                decode_catalog(&serde_json::to_vec(&catalog).unwrap()).is_err(),
                "{field}"
            );
        }
        for (field, value) in [
            ("m_InternalIds", json!([null])),
            ("m_ProviderIds", json!({})),
            ("m_resourceTypes", json!([{"m_className": BUNDLE_RESOURCE}])),
        ] {
            let mut catalog = base.clone();
            catalog[field] = value;
            assert!(
                decode_catalog(&serde_json::to_vec(&catalog).unwrap()).is_err(),
                "{field}"
            );
        }
    }

    #[test]
    fn rejects_negative_out_of_bounds_and_inconsistent_binary_tables() {
        let base = fixture(&[("font", "hash", "{App.WebServerConfig.Path}/font.bundle")]);
        for (field, offset, value) in [
            ("m_BucketDataString", 0, -1),
            ("m_BucketDataString", 0, i32::MAX),
            ("m_BucketDataString", 4, -1),
            ("m_BucketDataString", 4, 0),
            ("m_BucketDataString", 4, i32::MAX),
            ("m_BucketDataString", 8, -1),
            ("m_BucketDataString", 8, i32::MAX),
            ("m_BucketDataString", 12, -1),
            ("m_BucketDataString", 12, 1),
            ("m_KeyDataString", 0, -1),
            ("m_KeyDataString", 0, 2),
            ("m_KeyDataString", 5, -1),
            ("m_KeyDataString", 5, i32::MAX),
            ("m_EntryDataString", 0, -1),
            ("m_EntryDataString", 0, i32::MAX),
            ("m_EntryDataString", 4, -1),
            ("m_EntryDataString", 4, 1),
            ("m_EntryDataString", 8, -1),
            ("m_EntryDataString", 8, 1),
            ("m_EntryDataString", 12, -2),
            ("m_EntryDataString", 12, 1),
            ("m_EntryDataString", 20, -2),
            ("m_EntryDataString", 20, i32::MAX),
            ("m_EntryDataString", 24, -1),
            ("m_EntryDataString", 24, 1),
            ("m_EntryDataString", 28, -1),
            ("m_EntryDataString", 28, 1),
        ] {
            let mut catalog = base.clone();
            mutate_i32(&mut catalog, field, offset, value);
            assert!(
                decode_catalog(&serde_json::to_vec(&catalog).unwrap()).is_err(),
                "{field} offset={offset} value={value}"
            );
        }
        for field in ["m_BucketDataString", "m_EntryDataString"] {
            let mut catalog = base.clone();
            let mut data = bytes(&catalog, field);
            data.push(0);
            catalog[field] = json!(STANDARD.encode(data));
            assert!(
                decode_catalog(&serde_json::to_vec(&catalog).unwrap()).is_err(),
                "{field}"
            );
        }
    }

    #[test]
    fn every_truncated_binary_table_fails_without_panicking() {
        let base = fixture(&[("font", "hash", "{App.WebServerConfig.Path}/font.bundle")]);
        for field in [
            "m_BucketDataString",
            "m_KeyDataString",
            "m_EntryDataString",
            "m_ExtraDataString",
        ] {
            let data = bytes(&base, field);
            for len in 0..data.len() {
                let mut catalog = base.clone();
                catalog[field] = json!(STANDARD.encode(&data[..len]));
                assert!(
                    decode_catalog(&serde_json::to_vec(&catalog).unwrap()).is_err(),
                    "{field} length={len}"
                );
            }
        }
    }

    #[test]
    fn validates_serialized_primitive_forms_and_rejects_bad_strings() {
        for data in [
            vec![2, 0, 1],
            vec![3, 0, 0, 0, 0],
            vec![4, 255, 255, 255, 255],
            vec![5, 2, b'a', b'b'],
            vec![6, 2, b'a', b'b'],
        ] {
            assert!(read_object(&data, 0, "test").unwrap().is_none());
            for len in 0..data.len() {
                assert!(read_object(&data[..len], 0, "test").is_err());
            }
        }
        for data in [
            vec![],
            vec![255],
            vec![0, 255, 255, 255, 255],
            vec![0, 1, 0, 0, 0, 255],
            vec![1, 1, 0, 0, 0, 0],
            vec![1, 2, 0, 0, 0, 0, 216],
            vec![5, 1, 255],
            vec![7, 255],
        ] {
            assert!(read_object(&data, 0, "test").is_err(), "{data:?}");
        }
        assert!(read_object(&[2, 0, 0], usize::MAX, "test").is_err());
    }

    #[test]
    fn rejects_malformed_options_json_object_type_lengths_and_utf16() {
        let base = fixture(&[("font", "hash", "{App.WebServerConfig.Path}/font.bundle")]);
        for payload in [
            "{",
            "[]",
            "{}",
            r#"{"m_BundleName":4,"m_Hash":"hash","m_BundleSize":1,"m_Crc":0}"#,
            r#"{"m_BundleName":"font","m_Hash":"hash","m_BundleSize":-1,"m_Crc":0}"#,
            r#"{"m_BundleName":"../font","m_Hash":"hash","m_BundleSize":1,"m_Crc":0}"#,
        ] {
            let mut catalog = base.clone();
            catalog["m_ExtraDataString"] = json!(STANDARD.encode(object(payload)));
            assert!(
                decode_catalog(&serde_json::to_vec(&catalog).unwrap()).is_err(),
                "{payload}"
            );
        }
        let mut catalog = base.clone();
        mutate_i32(&mut catalog, "m_EntryDataString", 20, -1);
        assert!(decode_catalog(&serde_json::to_vec(&catalog).unwrap()).is_err());
        let good = object("{}");
        let length_offset =
            1 + 1 + usize::from(good[1]) + 1 + usize::from(good[2 + usize::from(good[1])]);
        for length in [-1_i32, 1, i32::MAX] {
            let mut data = good.clone();
            data[length_offset..length_offset + 4].copy_from_slice(&length.to_le_bytes());
            assert!(read_object(&data, 0, "test").is_err());
        }
        let mut data = good.clone();
        data[length_offset + 4..length_offset + 6].copy_from_slice(&0xd800_u16.to_le_bytes());
        assert!(read_object(&data, 0, "test").is_err());
        data = good;
        data[2] = 255; // invalid ASCII in the assembly name
        assert!(read_object(&data, 0, "test").is_err());
    }
}
