use std::fs;
use std::path::Path;

use astral_auto_patcher::catalog::resolve_manifest;
use astral_auto_patcher::{
    CatalogIdentity, GameInstallation, GameRoute, InstallRootBinding, InstallTarget, PatchManifest,
    PatcherPaths, PendingInstallManifest, assess_patch_files, install_patch, install_roots,
    installed_patch_change_count, load_ownership, reconcile_verified_install,
    restore_release_files,
};
use base64::{Engine, engine::general_purpose::STANDARD};
use serde_json::json;
use sha2::{Digest, Sha256};
use tempfile::tempdir;

const ORIGINAL: &[u8] = b"original";
const PATCHED: &[u8] = b"patched";
const FONT_NAME: &str = "bbf73ff295228ba6d340aa007997974e";
const FONT_HASH: &str = "bf9fdf4f4afafccd49732369c13d9c82";
const TEXT_NAME: &str = "f138d1971657be74d0155ab4d3cae489";
const TEXT_HASH: &str = "24ab65a9134830ac3b9adefeaa92421f";
const FONT_PATH: &str = "StreamingAssets/aa/StandaloneWindows64/font.bundle";

fn hash(data: &[u8]) -> String {
    format!("{:x}", Sha256::digest(data))
}

fn write(path: &Path, data: &[u8]) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, data).unwrap();
}

fn write_catalog(path: &Path, runtime_font: bool) {
    let mut entries = 2_i32.to_le_bytes().to_vec();
    let mut extra = Vec::new();
    for (index, (name, bundle_hash)) in [(TEXT_NAME, TEXT_HASH), (FONT_NAME, FONT_HASH)]
        .into_iter()
        .enumerate()
    {
        let offset = i32::try_from(extra.len()).unwrap();
        let options = json!({"m_BundleName": name, "m_Hash": bundle_hash,
            "m_BundleSize": ORIGINAL.len(), "m_Crc": 0})
        .to_string();
        let encoded: Vec<u8> = options.encode_utf16().flat_map(u16::to_le_bytes).collect();
        extra.push(7);
        for value in [
            "Unity.ResourceManager",
            "UnityEngine.ResourceManagement.ResourceProviders.AssetBundleRequestOptions",
        ] {
            extra.push(u8::try_from(value.len()).unwrap());
            extra.extend_from_slice(value.as_bytes());
        }
        extra.extend_from_slice(&i32::try_from(encoded.len()).unwrap().to_le_bytes());
        extra.extend_from_slice(&encoded);
        for value in [i32::try_from(index).unwrap(), 0, -1, 0, offset, 0, 0] {
            entries.extend_from_slice(&value.to_le_bytes());
        }
    }
    let font_id = if runtime_font {
        "{UnityEngine.AddressableAssets.Addressables.RuntimePath}\\StandaloneWindows64\\font.bundle"
    } else {
        "{App.WebServerConfig.Path}/font.bundle"
    };
    let catalog = json!({
        "m_InternalIds": ["{App.WebServerConfig.Path}/text.bundle", font_id],
        "m_ProviderIds": ["UnityEngine.ResourceManagement.ResourceProviders.AssetBundleProvider"],
        "m_resourceTypes": [{"m_ClassName": "UnityEngine.ResourceManagement.ResourceProviders.IAssetBundleResource"}],
        "m_EntryDataString": STANDARD.encode(entries),
        "m_ExtraDataString": STANDARD.encode(extra),
        "m_KeyDataString": STANDARD.encode([1, 0, 0, 0, 0, 1, 0, 0, 0, b'k']),
        "m_BucketDataString": STANDARD.encode([1, 0, 0, 0, 4, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0]),
        "m_InternalIdPrefixes": []
    });
    write(path, &serde_json::to_vec(&catalog).unwrap());
}

fn manifest(route: GameRoute) -> PatchManifest {
    let files = [
        ("addressables", format!("{TEXT_NAME}/{TEXT_HASH}/__data")),
        ("addressables", format!("{FONT_NAME}/{FONT_HASH}/__data")),
        ("game-data", "data.unity3d".to_owned()),
    ].map(|(target, path)| json!({
        "target": target, "path": path, "operation": "replace",
        "downloadUrl": "https://example.test/patched.gz", "downloadSha256": "d".repeat(64),
        "downloadSize": 20, "compression": "gzip", "sha256": hash(PATCHED), "size": PATCHED.len(),
        "sourceDownloadUrl": "https://example.test/original.gz",
        "sourceDownloadSha256": "e".repeat(64), "sourceDownloadSize": 21,
        "sourceSha256": hash(ORIGINAL), "sourceSize": ORIGINAL.len()
    }));
    serde_json::from_value(json!({
        "schemaVersion": 2,
        "patch": {"version": "v3.3.0_r012_p0", "channel": "release", "route": route.as_str(),
            "buildId": "test", "translationFingerprint": "a".repeat(64)},
        "game": {"version": "3.3.0", "revision": "012", "catalogHash": "b".repeat(32)},
        "files": files
    }))
    .unwrap()
}

#[test]
fn both_steam_routes_install_verify_and_restore_catalog_selected_targets() {
    check_catalog_selected_targets(false);
}

#[test]
fn both_steam_routes_use_game_directory_cache_with_stale_locallow_cache_present() {
    check_catalog_selected_targets(true);
}

fn check_catalog_selected_targets(game_cache_present: bool) {
    for route in GameRoute::ALL {
        for runtime_font in [false, true] {
            let temp = tempdir().unwrap();
            let addressables = temp.path().join("LocalLow/com.unity.addressables");
            let catalog_path = addressables.join("catalog_3.3.0.json");
            write_catalog(&catalog_path, runtime_font);
            let game = GameInstallation {
                route,
                game_root: temp.path().join("Steam"),
                game_data_root: temp.path().join("Steam").join(route.data_dir()),
                locallow_root: temp.path().join("LocalLow"),
                addressables_root: addressables,
                catalog: CatalogIdentity {
                    version: "3.3.0".into(),
                    hash: "b".repeat(32),
                    hash_path: catalog_path.with_extension("hash"),
                    catalog_path: Some(catalog_path),
                },
            };
            let published = manifest(route);
            let resolved =
                resolve_manifest(&published, game.catalog.catalog_path.as_deref()).unwrap();
            resolved.validate().unwrap();
            assert_eq!(
                resolved.files[1].target,
                if runtime_font {
                    InstallTarget::GameData
                } else {
                    InstallTarget::Addressables
                }
            );
            assert_eq!(
                resolved.files[1].path,
                if runtime_font {
                    FONT_PATH.to_owned()
                } else {
                    published.files[1].path.clone()
                }
            );
            // Transport URLs, digest, and originals remain the released values.
            assert_eq!(
                resolved.files[1].download_url,
                published.files[1].download_url
            );
            assert_eq!(
                resolved.files[1].source_sha256,
                published.files[1].source_sha256
            );
            let game_cache = game
                .game_data_root
                .join("com.unity.addressables/AssetBundles");
            if game_cache_present {
                fs::create_dir_all(&game_cache).unwrap();
            }
            let roots = install_roots(&game);
            let legacy_cache = game.addressables_root.join("AssetBundles");
            assert_eq!(
                roots.addressables,
                if game_cache_present {
                    game_cache
                } else {
                    legacy_cache.clone()
                }
            );
            let paths = PatcherPaths::below(temp.path().join("state"));
            let state = paths.route_state(route);
            let decoy = if runtime_font {
                roots.addressables.join(&published.files[1].path)
            } else {
                roots.game_data.join(FONT_PATH)
            };
            write(&decoy, b"unrelated cache or runtime file");
            for file in &resolved.files {
                if game_cache_present && file.target == InstallTarget::Addressables {
                    write(&legacy_cache.join(&file.path), b"stale LocalLow bundle");
                }
                let root = if file.target == InstallTarget::GameData {
                    &roots.game_data
                } else {
                    &roots.addressables
                };
                write(&root.join(&file.path), ORIGINAL);
                write(
                    &state
                        .staging_root
                        .join(file.target.staging_dir())
                        .join(&file.path),
                    PATCHED,
                );
            }
            assert_eq!(
                assess_patch_files(&resolved, &roots)
                    .unwrap()
                    .matching_files,
                0
            );
            let summary = install_patch(
                &resolved,
                &state.staging_root,
                &roots,
                &state.backup_root,
                &state.ownership_path,
            )
            .unwrap();
            assert_eq!(summary.modified, 3);
            let ownership = load_ownership(&state.ownership_path).unwrap().unwrap();
            assert_eq!(installed_patch_change_count(&ownership, &roots).unwrap(), 0);
            assert_eq!(
                assess_patch_files(&resolved, &roots)
                    .unwrap()
                    .matching_files,
                3
            );
            assert_eq!(ownership.modified_files[1].target, resolved.files[1].target);
            assert_eq!(ownership.modified_files[1].path, resolved.files[1].path);

            // Reconciliation starts from the unmodified, fetched manifest just like the service.
            let pending = PendingInstallManifest {
                manifest: resolved.clone(),
                root_binding: InstallRootBinding::from_roots(&roots),
            };
            write(
                &state.pending_manifest_path,
                &serde_json::to_vec(&pending).unwrap(),
            );
            fs::remove_file(&state.ownership_path).unwrap();
            assert!(reconcile_verified_install(&paths, &game, &published).unwrap());
            assert!(!state.pending_manifest_path.exists());
            let saved: PatchManifest =
                serde_json::from_slice(&fs::read(&state.manifest_path).unwrap()).unwrap();
            assert_eq!(saved, resolved);

            let originals = temp.path().join("released-originals");
            for file in &saved.files {
                write(
                    &originals.join(file.target.staging_dir()).join(&file.path),
                    ORIGINAL,
                );
            }
            let report = restore_release_files(&saved, &roots, &originals).unwrap();
            assert_eq!(report.restored, 3);
            assert!(report.issues.is_empty());
            for file in &saved.files {
                let root = if file.target == InstallTarget::GameData {
                    &roots.game_data
                } else {
                    &roots.addressables
                };
                assert_eq!(fs::read(root.join(&file.path)).unwrap(), ORIGINAL);
                if game_cache_present && file.target == InstallTarget::Addressables {
                    assert_eq!(
                        fs::read(legacy_cache.join(&file.path)).unwrap(),
                        b"stale LocalLow bundle"
                    );
                }
            }
            assert_eq!(fs::read(decoy).unwrap(), b"unrelated cache or runtime file");
        }
    }
}
