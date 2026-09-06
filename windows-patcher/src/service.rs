use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::game::{GameInstallation, GameRoute};
use crate::install::{
    ApplyPhase, ApplyProgress, InstallError, InstallRootBinding, InstallRoots, InstallSummary,
    OwnershipManifest, RemoveIssueSummary, RemoveReport, assess_patch_files, atomic_write,
    install_patch_with_progress, installed_patch_change_count, restore_release_files,
    validate_patch_targets,
};
use crate::logging;
use crate::network::{NetworkError, ReleaseClient, StageProgress};
use crate::protocol::PatchManifest;

pub const RELEASE_CHANNEL: &str = "release";

#[derive(Debug, Error)]
pub enum ServiceError {
    #[error(transparent)]
    Game(#[from] crate::game::GameDetectError),
    #[error(transparent)]
    Network(#[from] NetworkError),
    #[error(transparent)]
    Install(#[from] InstallError),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("failed to parse ownership manifest: {0}")]
    OwnershipJson(#[from] serde_json::Error),
    #[error("existing patch state changed externally: {0} file(s)")]
    ExistingPatchChanged(usize),
    #[error("existing patch cannot be safely removed: {0}")]
    ExistingPatchUnsafe(RemoveIssueSummary),
    #[error("legacy patch state migration conflict: {legacy_path} -> {destination}")]
    StateMigrationConflict {
        legacy_path: PathBuf,
        destination: PathBuf,
    },
    #[error(
        "patch target list changed; remove the existing Korean patch before installing this release"
    )]
    ChangedPatchTargets,
    #[error("manifest is not compatible with the detected game")]
    IncompatibleManifest,
}

#[derive(Debug, Clone)]
pub struct PatcherPaths {
    pub state_root: PathBuf,
    pub routes_root: PathBuf,
    pub logs_root: PathBuf,
    pub settings_path: PathBuf,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RouteStatePaths {
    pub root: PathBuf,
    pub staging_root: PathBuf,
    pub backup_root: PathBuf,
    pub ownership_path: PathBuf,
    pub manifest_path: PathBuf,
    pub pending_manifest_path: PathBuf,
}

/// A manifest that is being applied, together with the game roots it targets.
///
/// Keeping this context in the pending record makes an interrupted operation safe to resume or
/// remove after the user points the patcher at another game installation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingInstallManifest {
    pub manifest: PatchManifest,
    pub root_binding: InstallRootBinding,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StateMigrationItem {
    pub source: PathBuf,
    pub destination: PathBuf,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct StateMigrationReport {
    pub moved: Vec<StateMigrationItem>,
}

impl PatcherPaths {
    pub fn below(state_root: PathBuf) -> Self {
        Self {
            routes_root: state_root.join("routes"),
            logs_root: state_root.join("logs"),
            settings_path: state_root.join("settings.json"),
            state_root,
        }
    }

    #[cfg(windows)]
    pub fn windows_default() -> Result<Self, ServiceError> {
        let local_app_data = std::env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .ok_or_else(|| {
                std::io::Error::new(std::io::ErrorKind::NotFound, "LOCALAPPDATA is not set")
            })?;
        Ok(Self::below(local_app_data.join("AstralAutoPatcher")))
    }

    pub fn route_state(&self, route: GameRoute) -> RouteStatePaths {
        self.route_state_slug(route.slug())
    }

    fn route_state_slug(&self, slug: &str) -> RouteStatePaths {
        let root = self.routes_root.join(slug);
        RouteStatePaths {
            staging_root: root.join("staging"),
            backup_root: root.join("backup"),
            ownership_path: root.join("installed.json"),
            manifest_path: root.join("installed-manifest.json"),
            pending_manifest_path: root.join("pending-manifest.json"),
            root,
        }
    }
}

impl RouteStatePaths {
    pub fn reset_staging(&self) -> Result<(), std::io::Error> {
        if self.staging_root.exists() {
            fs::remove_dir_all(&self.staging_root)?;
        }
        fs::create_dir_all(&self.staging_root)
    }
}

pub fn migrate_legacy_state(paths: &PatcherPaths) -> Result<StateMigrationReport, ServiceError> {
    let int_state = paths.route_state(GameRoute::IntSteam);
    let cn_state = paths.route_state(GameRoute::CnSteam);

    // CN legacy backup/staging lived below the INT legacy directories. Move those children first
    // so the remaining parent directories contain only INT state when they are migrated.
    // Steam state is persistent ownership data, so it is never overwritten automatically.
    let steam_moves = [
        (
            paths.state_root.join("installed-cn-steam.json"),
            cn_state.ownership_path.clone(),
        ),
        (
            paths.state_root.join("backup").join("cn-steam"),
            cn_state.backup_root.clone(),
        ),
        (
            paths.state_root.join("staging").join("cn-steam"),
            cn_state.staging_root.clone(),
        ),
        (
            paths.state_root.join("installed.json"),
            int_state.ownership_path.clone(),
        ),
        (
            paths.state_root.join("backup"),
            int_state.backup_root.clone(),
        ),
        (
            paths.state_root.join("staging"),
            int_state.staging_root.clone(),
        ),
    ];

    for (source, destination) in &steam_moves {
        if source.exists() && destination.exists() {
            return Err(ServiceError::StateMigrationConflict {
                legacy_path: source.clone(),
                destination: destination.clone(),
            });
        }
    }

    let mut report = StateMigrationReport::default();
    for (source, destination) in steam_moves {
        move_legacy_path(&source, &destination, &mut report)?;
    }

    Ok(report)
}

fn move_legacy_path(
    source: &Path,
    destination: &Path,
    report: &mut StateMigrationReport,
) -> Result<(), ServiceError> {
    if !source.exists() {
        return Ok(());
    }
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::rename(source, destination)?;
    report.moved.push(StateMigrationItem {
        source: source.to_owned(),
        destination: destination.to_owned(),
    });
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstalledPatchInfo {
    pub patch_version: String,
    pub catalog_hash: String,
    #[serde(default)]
    pub installed_at: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PatchStateResetReport {
    pub ownership_removed: bool,
    pub backup_removed: bool,
    pub staging_removed: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstallOutcome {
    AlreadyInstalled(InstalledPatchInfo),
    Installed(InstallSummary),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PatchFileInfo {
    pub download_name: String,
    pub install_path: String,
    pub download_size: u64,
    pub install_size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstallProgress {
    Resolving,
    Selected {
        patch_version: String,
        files: Vec<PatchFileInfo>,
        download_total: u64,
        install_total: u64,
    },
    Downloading {
        file_index: usize,
        file_count: usize,
        file_name: String,
        current: u64,
        total: u64,
    },
    Extracting {
        file_index: usize,
        file_count: usize,
        file_name: String,
        current: u64,
        total: u64,
    },
    RemovingExisting {
        patch_version: String,
    },
    Applying {
        file_index: usize,
        file_count: usize,
        path: String,
        phase: ApplyPhase,
        current: u64,
        total: u64,
    },
}

pub fn install_roots(game: &GameInstallation) -> InstallRoots {
    InstallRoots {
        addressables: game.addressables_root.join("AssetBundles"),
        game_data: game.game_data_root.clone(),
    }
}

pub fn load_ownership(path: &Path) -> Result<Option<OwnershipManifest>, ServiceError> {
    if !path.is_file() {
        return Ok(None);
    }
    let raw = fs::read(path)?;
    Ok(Some(serde_json::from_slice(&raw)?))
}

/// Load ownership only when it explicitly belongs to the selected game roots.
///
/// Older records do not carry a root binding and therefore are deliberately ignored by mutation
/// paths.  A caller can still inspect them with [`load_ownership`] and repair them after verifying
/// the complete current manifest against the selected files.
pub fn load_ownership_for_roots(
    path: &Path,
    roots: &InstallRoots,
) -> Result<Option<OwnershipManifest>, ServiceError> {
    Ok(load_ownership(path)?.filter(|ownership| ownership.applies_to(roots)))
}

/// Upgrade an installation record written before root binding was introduced.
///
/// A legacy record is bound to the selected roots only when every recorded patched file still
/// matches there. This keeps path retargeting safe while preserving update detection for existing
/// users after upgrading the patcher itself.
pub fn bind_verified_legacy_ownership_for_roots(
    path: &Path,
    roots: &InstallRoots,
    catalog_hash: &str,
) -> Result<bool, ServiceError> {
    let Some(mut ownership) = load_ownership(path)? else {
        return Ok(false);
    };
    if ownership.catalog_hash != catalog_hash
        || ownership.root_binding.is_some()
        || (ownership.created_files.is_empty() && ownership.modified_files.is_empty())
        || installed_patch_change_count(&ownership, roots)? != 0
    {
        return Ok(false);
    }

    ownership.root_binding = Some(InstallRootBinding::from_roots(roots));
    write_json_atomic(path, &ownership)?;
    logging::info(format!(
        "Bound legacy installation record to current roots: {}",
        path.display()
    ));
    Ok(true)
}

pub fn load_pending_manifest(path: &Path) -> Result<Option<PendingInstallManifest>, ServiceError> {
    if !path.is_file() {
        return Ok(None);
    }
    let raw = fs::read(path)?;
    let pending = serde_json::from_slice(&raw)?;
    Ok(Some(pending))
}

fn write_json_atomic<T: Serialize>(path: &Path, value: &T) -> Result<(), ServiceError> {
    let json = serde_json::to_vec_pretty(value)?;
    atomic_write(path, &json)?;
    Ok(())
}

pub fn installed_patch_info(path: &Path) -> Result<Option<InstalledPatchInfo>, ServiceError> {
    Ok(load_ownership(path)?.map(|ownership| InstalledPatchInfo {
        patch_version: ownership.patch_version,
        catalog_hash: ownership.catalog_hash,
        installed_at: ownership.installed_at,
    }))
}

pub fn reset_patch_state(
    paths: &PatcherPaths,
    route: GameRoute,
) -> Result<PatchStateResetReport, ServiceError> {
    let state = paths.route_state(route);
    logging::warn(format!(
        "resetting patch state: route={} root={}",
        route.as_str(),
        state.root.display()
    ));
    let ownership_removed = state.ownership_path.exists();
    let backup_removed = state.backup_root.exists();
    let staging_removed = state.staging_root.exists();

    if staging_removed {
        fs::remove_dir_all(&state.staging_root)?;
    }
    if backup_removed {
        fs::remove_dir_all(&state.backup_root)?;
    }
    if ownership_removed {
        fs::remove_file(&state.ownership_path)?;
    }
    if state.manifest_path.exists() {
        fs::remove_file(&state.manifest_path)?;
    }
    if state.pending_manifest_path.exists() {
        fs::remove_file(&state.pending_manifest_path)?;
    }

    Ok(PatchStateResetReport {
        ownership_removed,
        backup_removed,
        staging_removed,
    })
}

pub fn remove_compatible_patch(
    release_index_url: &str,
    paths: &PatcherPaths,
    game: &GameInstallation,
) -> Result<RemoveReport, ServiceError> {
    let client = ReleaseClient::new(&format!("AstralAutoPatcher/{}", env!("CARGO_PKG_VERSION")))?;
    let index = client.fetch_release_index(release_index_url)?;
    let (_, manifest) = client.fetch_compatible_manifest(
        &index,
        game.route.as_str(),
        &game.catalog.version,
        &game.catalog.hash,
        RELEASE_CHANNEL,
    )?;
    ensure_manifest_compatible(&manifest, game, game.route.as_str())?;
    let mut restore_manifest = manifest;
    let state = paths.route_state(game.route);
    let roots = install_roots(game);
    // Historical metadata is only used for targets omitted by the current release.
    // Every original is still downloaded and verified against its release metadata.
    let ownership_matches = load_ownership_for_roots(&state.ownership_path, &roots)
        .ok()
        .flatten()
        .is_some();
    if ownership_matches
        && let Ok(raw) = fs::read(&state.manifest_path)
        && let Ok(previous) = serde_json::from_slice::<PatchManifest>(&raw)
        && previous.validate().is_ok()
        && ensure_manifest_compatible(&previous, game, game.route.as_str()).is_ok()
    {
        merge_restore_targets(&mut restore_manifest, &previous, &roots)?;
    }
    if let Ok(Some(pending)) = load_pending_manifest(&state.pending_manifest_path)
        && pending.root_binding.matches_roots(&roots)
        && pending.manifest.validate().is_ok()
        && ensure_manifest_compatible(&pending.manifest, game, game.route.as_str()).is_ok()
    {
        merge_restore_targets(&mut restore_manifest, &pending.manifest, &roots)?;
    }
    remove_release_manifest(&client, paths, &roots, game.route, &restore_manifest)
}

fn merge_restore_targets(
    current: &mut PatchManifest,
    previous: &PatchManifest,
    roots: &InstallRoots,
) -> Result<(), InstallError> {
    for old in &previous.files {
        if let Some(new) = current
            .files
            .iter_mut()
            .find(|new| new.target == old.target && new.path == old.path)
        {
            // A newer create entry has no original metadata; retain the prior
            // replacement's released original so removal can recover this path.
            if old.operation == "replace" && new.operation == "create" {
                *new = old.clone();
                continue;
            }
            // Both releases added this path: recognize an intact older patch too.
            if old.operation == "create" && new.operation == "create" {
                let root = match old.target {
                    crate::protocol::InstallTarget::GameData => &roots.game_data,
                    crate::protocol::InstallTarget::Addressables => &roots.addressables,
                };
                let target = root.join(&old.path);
                if target.is_file()
                    && target.metadata()?.len() == old.size
                    && crate::install::sha256_file(&target)? == old.sha256
                {
                    *new = old.clone();
                }
            }
        } else {
            current.files.push(old.clone());
        }
    }
    Ok(())
}

/// Compatibility entry point for the terminal UI. Originals are always downloaded anew.
pub fn remove_installed_patch(
    paths: &PatcherPaths,
    roots: &InstallRoots,
    route: GameRoute,
) -> Result<Option<RemoveReport>, ServiceError> {
    let state = paths.route_state(route);
    let ownership = load_ownership_for_roots(&state.ownership_path, roots)?;
    let pending = load_pending_manifest(&state.pending_manifest_path)?
        .filter(|pending| pending.root_binding.matches_roots(roots))
        .filter(|pending| pending.manifest.validate().is_ok());
    // A manifest without an ownership/root binding may describe a different game directory.
    // Pending records carry their own binding and are safe to use for an interrupted install.
    if ownership.is_none() && pending.is_none() {
        return Ok(None);
    }
    let mut manifest = if state.manifest_path.is_file() {
        serde_json::from_slice::<PatchManifest>(&fs::read(&state.manifest_path)?)?
    } else if let Some(pending) = pending.as_ref() {
        pending.manifest.clone()
    } else {
        return Ok(None);
    };
    manifest.validate().map_err(InstallError::from)?;
    let catalog_root = roots
        .addressables
        .parent()
        .ok_or(ServiceError::IncompatibleManifest)?;
    let catalog = crate::game::discover_latest_catalog(catalog_root)?;
    if manifest.patch.route != route.as_str()
        || manifest.game.version != catalog.version
        || manifest.game.catalog_hash != catalog.hash
    {
        return Err(ServiceError::IncompatibleManifest);
    }
    if let Some(pending) = pending.as_ref() {
        merge_restore_targets(&mut manifest, &pending.manifest, roots)?;
    }
    let client = ReleaseClient::new(&format!("AstralAutoPatcher/{}", env!("CARGO_PKG_VERSION")))?;
    remove_release_manifest(&client, paths, roots, route, &manifest).map(Some)
}

fn remove_release_manifest(
    client: &ReleaseClient,
    paths: &PatcherPaths,
    roots: &InstallRoots,
    route: GameRoute,
    manifest: &PatchManifest,
) -> Result<RemoveReport, ServiceError> {
    let state = paths.route_state(route);
    state.reset_staging()?;
    // A fresh staging directory ensures no local originals or stale downloads are reused.
    for file in &manifest.files {
        if file.operation == "replace" {
            logging::info(format!("Downloading release original: {}", file.path));
            client.download_original_file(
                file,
                &state
                    .staging_root
                    .join(file.target.staging_dir())
                    .join(&file.path),
            )?;
        }
    }
    let report = restore_release_files(manifest, roots, &state.staging_root)?;
    // Keep the journal when any file is externally changed so removal can be retried.  Also do
    // not clear metadata from a previous game folder after the user retargets the patcher.
    if report.issues.is_empty() {
        let ownership_matches = load_ownership_for_roots(&state.ownership_path, roots)
            .ok()
            .flatten()
            .is_some();
        if ownership_matches {
            for path in [&state.ownership_path, &state.manifest_path] {
                if path.exists() {
                    fs::remove_file(path)?;
                }
            }
        }
        let pending_matches = load_pending_manifest(&state.pending_manifest_path)
            .ok()
            .flatten()
            .is_some_and(|pending| pending.root_binding.matches_roots(roots));
        if pending_matches && state.pending_manifest_path.exists() {
            fs::remove_file(&state.pending_manifest_path)?;
        }
    }
    let _ = fs::remove_dir_all(&state.staging_root);
    logging::info(format!(
        "Release restore complete: restored={} removed={} preserved={}",
        report.restored,
        report.removed,
        report.issues.len()
    ));
    Ok(report)
}

fn ensure_manifest_compatible(
    manifest: &PatchManifest,
    game: &GameInstallation,
    route: &str,
) -> Result<(), ServiceError> {
    if manifest.patch.route != route
        || manifest.game.version != game.catalog.version
        || manifest.game.catalog_hash != game.catalog.hash
    {
        return Err(ServiceError::IncompatibleManifest);
    }
    Ok(())
}

fn stage_before_existing_removal<S>(
    state: &RouteStatePaths,
    existing: Option<&OwnershipManifest>,
    manifest: &PatchManifest,
    roots: &InstallRoots,
    _route: GameRoute,
    progress: &mut dyn FnMut(InstallProgress),
    stage: S,
) -> Result<(), ServiceError>
where
    S: FnOnce(&Path, &mut dyn FnMut(InstallProgress)) -> Result<(), ServiceError>,
{
    // Keep the currently installed patch intact until every new transport file has been
    // downloaded, decompressed, and verified in staging.
    state.reset_staging()?;
    stage(&state.staging_root, progress)?;

    if let Some(existing) = existing.filter(|record| record.applies_to(roots)) {
        // Only delete an old added file if its contents still match the recorded patch.
        let mut created = Vec::new();
        for file in &existing.created_files {
            if manifest.files.iter().any(|new| {
                new.target == file.target
                    && new.path == file.path
                    && (new.operation == "replace" || new.sha256 == file.installed_sha256)
            }) {
                continue;
            }
            crate::protocol::validate_relative_path(&file.path).map_err(InstallError::from)?;
            let root = match file.target {
                crate::protocol::InstallTarget::GameData => &roots.game_data,
                crate::protocol::InstallTarget::Addressables => &roots.addressables,
            };
            let path = root.join(&file.path);
            if path.is_file() && crate::install::sha256_file(&path)? == file.installed_sha256 {
                created.push(path);
            }
        }
        for path in created {
            fs::remove_file(path)?;
        }
    }
    Ok(())
}

fn ownership_for_manifest(
    manifest: &PatchManifest,
    roots: &InstallRoots,
    installed_at: Option<String>,
) -> OwnershipManifest {
    let mut ownership = OwnershipManifest {
        schema_version: 1,
        patch_version: manifest.patch.version.clone(),
        catalog_hash: manifest.game.catalog_hash.clone(),
        installed_at,
        root_binding: Some(InstallRootBinding::from_roots(roots)),
        created_files: Vec::new(),
        modified_files: Vec::new(),
    };
    for file in &manifest.files {
        match file.operation.as_str() {
            "create" => ownership
                .created_files
                .push(crate::install::OwnedCreatedFile {
                    target: file.target,
                    path: file.path.clone(),
                    installed_sha256: file.sha256.clone(),
                }),
            "replace" => ownership
                .modified_files
                .push(crate::install::OwnedModifiedFile {
                    target: file.target,
                    path: file.path.clone(),
                    original_sha256: file.source_sha256.clone().unwrap_or_default(),
                    patched_sha256: file.sha256.clone(),
                    backup_path: String::new(),
                }),
            _ => unreachable!("manifest validation rejects unsupported operations"),
        }
    }
    ownership
}

/// Reconcile a fully verified install after an interrupted operation.
///
/// The pending journal is required so a background verification cannot manufacture an install
/// record merely because files happen to have the same hashes as a release.  The regular install
/// path uses the same implementation without that requirement when it has just resolved a
/// release itself.
pub fn reconcile_verified_install(
    paths: &PatcherPaths,
    game: &GameInstallation,
    manifest: &PatchManifest,
) -> Result<bool, ServiceError> {
    reconcile_verified_manifest(paths, game, manifest, true)
}

fn reconcile_verified_manifest(
    paths: &PatcherPaths,
    game: &GameInstallation,
    manifest: &PatchManifest,
    require_pending: bool,
) -> Result<bool, ServiceError> {
    manifest.validate().map_err(InstallError::from)?;
    ensure_manifest_compatible(manifest, game, game.route.as_str())?;
    let roots = install_roots(game);
    let state = paths.route_state(game.route);
    let pending = load_pending_manifest(&state.pending_manifest_path)?
        .filter(|pending| pending.root_binding.matches_roots(&roots))
        .filter(|pending| {
            pending.manifest.validate().is_ok()
                && pending.manifest.patch.version == manifest.patch.version
                && pending.manifest.game.catalog_hash == manifest.game.catalog_hash
        });
    if require_pending && pending.is_none() {
        return Ok(false);
    }
    let assessment = assess_patch_files(manifest, &roots)?;
    if assessment.total_files == 0 || assessment.matching_files != assessment.total_files {
        return Ok(false);
    }
    let existing = load_ownership_for_roots(&state.ownership_path, &roots)
        .ok()
        .flatten();
    let installed_at = existing
        .as_ref()
        .filter(|old| {
            old.patch_version == manifest.patch.version
                && old.catalog_hash == manifest.game.catalog_hash
        })
        .and_then(|old| old.installed_at.clone());
    let ownership = ownership_for_manifest(manifest, &roots, installed_at);
    write_json_atomic(&state.ownership_path, &ownership)?;
    write_json_atomic(&state.manifest_path, manifest)?;
    if pending.is_some()
        && let Err(error) = fs::remove_file(&state.pending_manifest_path)
    {
        logging::warn(format!(
            "verified install metadata repaired but pending journal cleanup failed: {error}"
        ));
    }
    Ok(true)
}

pub fn install_latest_compatible(
    release_index_url: &str,
    paths: &PatcherPaths,
    game: &GameInstallation,
) -> Result<InstallOutcome, ServiceError> {
    install_latest_compatible_with_progress(release_index_url, paths, game, |_| {})
}

pub fn install_latest_compatible_with_progress<F>(
    release_index_url: &str,
    paths: &PatcherPaths,
    game: &GameInstallation,
    mut progress: F,
) -> Result<InstallOutcome, ServiceError>
where
    F: FnMut(InstallProgress),
{
    let roots = install_roots(game);
    let state = paths.route_state(game.route);
    let route = game.route.as_str();
    logging::info(format!(
        "Steam install resolve: route={} game_version={} catalog={} state={}",
        route,
        game.catalog.version,
        game.catalog.hash,
        state.root.display()
    ));
    let user_agent = format!("AstralAutoPatcher/{}", env!("CARGO_PKG_VERSION"));
    let client = ReleaseClient::new(&user_agent)?;
    progress(InstallProgress::Resolving);
    let index = client.fetch_release_index(release_index_url)?;
    let (_, manifest) = client.fetch_compatible_manifest(
        &index,
        route,
        &game.catalog.version,
        &game.catalog.hash,
        RELEASE_CHANNEL,
    )?;
    ensure_manifest_compatible(&manifest, game, route)?;
    logging::info(format!(
        "Steam manifest selected: route={} patch={} files={}",
        route,
        manifest.patch.version,
        manifest.files.len()
    ));

    let files = manifest
        .files
        .iter()
        .map(|file| PatchFileInfo {
            download_name: download_name(&file.download_url),
            install_path: file.path.clone(),
            download_size: file.download_size,
            install_size: file.size,
        })
        .collect::<Vec<_>>();
    let download_total = manifest.files.iter().map(|file| file.download_size).sum();
    let install_total = manifest.files.iter().map(|file| file.size).sum();
    progress(InstallProgress::Selected {
        patch_version: manifest.patch.version.clone(),
        files,
        download_total,
        install_total,
    });

    let existing = match load_ownership_for_roots(&state.ownership_path, &roots) {
        Ok(value) => value,
        Err(error) => {
            logging::warn(format!("Ignoring unreadable installation record: {error}"));
            None
        }
    };
    if existing
        .as_ref()
        .filter(|old| old.catalog_hash == manifest.game.catalog_hash)
        .is_some_and(|old| {
            old.modified_files.iter().any(|file| {
                !manifest.files.iter().any(|new| {
                    new.target == file.target && new.path == file.path && new.operation == "replace"
                })
            })
        })
    {
        return Err(ServiceError::ChangedPatchTargets);
    }
    let assessment = assess_patch_files(&manifest, &roots)?;
    if assessment.total_files > 0 && assessment.matching_files == assessment.total_files {
        // A previous process may have applied every file and failed before promoting metadata.
        // Rebuild the record from the verified manifest, preserving a timestamp only when it is
        // known to belong to this exact patch and root binding.
        let installed_at = existing
            .as_ref()
            .filter(|old| {
                old.patch_version == manifest.patch.version
                    && old.catalog_hash == manifest.game.catalog_hash
            })
            .and_then(|old| old.installed_at.clone());
        let ownership = ownership_for_manifest(&manifest, &roots, installed_at.clone());
        write_json_atomic(&state.ownership_path, &ownership)?;
        write_json_atomic(&state.manifest_path, &manifest)?;
        if let Ok(Some(pending)) = load_pending_manifest(&state.pending_manifest_path)
            && pending.root_binding.matches_roots(&roots)
            && let Err(error) = fs::remove_file(&state.pending_manifest_path)
        {
            logging::warn(format!(
                "installed files verified but pending journal cleanup failed: {error}"
            ));
        }
        return Ok(InstallOutcome::AlreadyInstalled(InstalledPatchInfo {
            patch_version: manifest.patch.version.clone(),
            catalog_hash: manifest.game.catalog_hash.clone(),
            installed_at,
        }));
    }
    for file in &manifest.files {
        let root = match file.target {
            crate::protocol::InstallTarget::GameData => &roots.game_data,
            crate::protocol::InstallTarget::Addressables => &roots.addressables,
        };
        let target = root.join(&file.path);
        if file.operation == "replace" && !target.is_file() {
            return Err(InstallError::ReplaceTargetMissing(target).into());
        }
        if assessment.conflicting_create.contains(&file.path) {
            let known = existing
                .as_ref()
                .filter(|old| old.catalog_hash == manifest.game.catalog_hash)
                .is_some_and(|old| {
                    old.created_files.iter().any(|created| {
                        created.target == file.target
                            && created.path == file.path
                            && target.is_file()
                            && crate::install::sha256_file(&target).ok().as_deref()
                                == Some(created.installed_sha256.as_str())
                    })
                });
            if !known {
                return Err(InstallError::CreateTargetExists(target).into());
            }
        }
    }

    // This is the operation journal.  It must be durable before any game file is retired or
    // replaced, and it intentionally remains in place when staging or installation fails.
    let pending = PendingInstallManifest {
        manifest: manifest.clone(),
        root_binding: InstallRootBinding::from_roots(&roots),
    };
    write_json_atomic(&state.pending_manifest_path, &pending)?;

    if existing.is_some() {
        progress(InstallProgress::RemovingExisting {
            patch_version: existing
                .as_ref()
                .map(|old| old.patch_version.clone())
                .unwrap_or_default(),
        });
    }
    stage_before_existing_removal(
        &state,
        existing
            .as_ref()
            .filter(|old| old.catalog_hash == manifest.game.catalog_hash),
        &manifest,
        &roots,
        game.route,
        &mut progress,
        |staging_root, progress| {
            client.stage_manifest_files_with_progress(
                &manifest,
                staging_root,
                |event| match event {
                    StageProgress::Downloading {
                        file_index,
                        file_count,
                        file_name,
                        current,
                        total,
                    } => progress(InstallProgress::Downloading {
                        file_index,
                        file_count,
                        file_name,
                        current,
                        total,
                    }),
                    StageProgress::Extracting {
                        file_index,
                        file_count,
                        file_name,
                        current,
                        total,
                    } => progress(InstallProgress::Extracting {
                        file_index,
                        file_count,
                        file_name,
                        current,
                        total,
                    }),
                },
            )?;
            Ok(())
        },
    )?;

    // Check again after retiring known files added by the previous patch.
    if existing.is_some() {
        validate_patch_targets(&manifest, &roots)?;
    }

    let summary = install_patch_with_progress(
        &manifest,
        &state.staging_root,
        &roots,
        &state.backup_root,
        &state.ownership_path,
        |ApplyProgress {
             file_index,
             file_count,
             path,
             phase,
             current,
             total,
         }| {
            progress(InstallProgress::Applying {
                file_index,
                file_count,
                path,
                phase,
                current,
                total,
            });
        },
    )?;
    write_json_atomic(&state.manifest_path, &manifest)?;
    if let Err(error) = fs::remove_file(&state.pending_manifest_path) {
        logging::warn(format!(
            "patch installed but pending journal cleanup failed: {error}"
        ));
    }
    let _ = fs::remove_dir_all(&state.staging_root);
    logging::info(format!(
        "Steam install complete: route={} patch={} created={} modified={}",
        route, manifest.patch.version, summary.created, summary.modified
    ));
    Ok(InstallOutcome::Installed(summary))
}

fn download_name(url: &str) -> String {
    url.rsplit('/')
        .next()
        .filter(|value| !value.is_empty())
        .unwrap_or(url)
        .to_owned()
}

#[cfg(test)]
mod tests {
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;

    use super::*;
    use crate::install::{install_patch, installed_patch_change_count};
    use crate::protocol::{InstallTarget, ManifestFile, PatchManifest, PatchMetadata, TargetGame};

    #[test]
    fn download_name_extracts_release_asset_name() {
        assert_eq!(
            download_name("https://example.test/releases/v1/assets/game-data-data.unity3d.gz"),
            "game-data-data.unity3d.gz"
        );
    }

    fn manifest(version: &str, hash: &str) -> PatchManifest {
        PatchManifest {
            schema_version: 2,
            patch: PatchMetadata {
                version: version.into(),
                channel: "release".into(),
                route: GameRoute::IntSteam.as_str().into(),
                build_id: "build".into(),
                translation_fingerprint: "a".repeat(64),
            },
            game: TargetGame {
                version: "3.2.0".into(),
                revision: "1042".into(),
                catalog_hash: "b".repeat(32),
            },
            files: vec![ManifestFile {
                target: InstallTarget::GameData,
                path: "data.unity3d".into(),
                operation: "replace".into(),
                download_url: "https://example.test/data.gz".into(),
                download_sha256: "d".repeat(64),
                download_size: 5,
                compression: "gzip".into(),
                sha256: hash.into(),
                size: 7,
                source_download_url: None,
                source_download_sha256: None,
                source_download_size: None,
                source_sha256: None,
                source_size: None,
            }],
        }
    }

    #[test]
    fn upgrade_overwrites_patch_without_original_backup() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        let roots = InstallRoots {
            addressables: temp.path().join("addressables"),
            game_data: temp.path().join("game-data"),
        };
        fs::create_dir_all(&roots.game_data).unwrap();
        fs::write(roots.game_data.join("data.unity3d"), b"original").unwrap();

        let payload = b"patch01";
        let hash = format!("{:x}", Sha256::digest(payload));
        let first = manifest("v1", &hash);
        let state = paths.route_state(GameRoute::IntSteam);
        let stage = state.staging_root.join("game-data/data.unity3d");
        fs::create_dir_all(stage.parent().unwrap()).unwrap();
        fs::write(&stage, payload).unwrap();
        install_patch(
            &first,
            &state.staging_root,
            &roots,
            &state.backup_root,
            &state.ownership_path,
        )
        .unwrap();

        let ownership = installed_patch_info(&state.ownership_path)
            .unwrap()
            .unwrap();
        assert_eq!(ownership.patch_version, "v1");
        assert!(ownership.installed_at.is_some());
        assert!(!state.backup_root.exists());
        fs::write(&stage, b"patch02").unwrap();
        let next = manifest("v2", &format!("{:x}", Sha256::digest(b"patch02")));
        install_patch(
            &next,
            &state.staging_root,
            &roots,
            &state.backup_root,
            &state.ownership_path,
        )
        .unwrap();
        assert_eq!(
            fs::read(roots.game_data.join("data.unity3d")).unwrap(),
            b"patch02"
        );
        assert_eq!(
            installed_patch_info(&state.ownership_path)
                .unwrap()
                .unwrap()
                .patch_version,
            "v2"
        );
        assert!(!state.backup_root.exists());
    }

    #[test]
    fn staging_failure_preserves_existing_installed_patch() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        let roots = InstallRoots {
            addressables: temp.path().join("addressables"),
            game_data: temp.path().join("game-data"),
        };
        fs::create_dir_all(&roots.game_data).unwrap();
        let target = roots.game_data.join("data.unity3d");
        fs::write(&target, b"original").unwrap();

        let old_payload = b"patch01";
        let old_manifest = manifest("v1", &format!("{:x}", Sha256::digest(old_payload)));
        let state = paths.route_state(GameRoute::IntSteam);
        let old_stage = state.staging_root.join("game-data/data.unity3d");
        fs::create_dir_all(old_stage.parent().unwrap()).unwrap();
        fs::write(&old_stage, old_payload).unwrap();
        install_patch(
            &old_manifest,
            &state.staging_root,
            &roots,
            &state.backup_root,
            &state.ownership_path,
        )
        .unwrap();
        let old_ownership = fs::read(&state.ownership_path).unwrap();
        let old_ownership_manifest = load_ownership(&state.ownership_path).unwrap().unwrap();

        let mut events = Vec::new();
        let error = stage_before_existing_removal(
            &state,
            Some(&old_ownership_manifest),
            &old_manifest,
            &roots,
            GameRoute::IntSteam,
            &mut |event| events.push(event),
            |_staging_root, _progress| {
                Err(ServiceError::Network(NetworkError::NoCompatibleRelease))
            },
        )
        .unwrap_err();

        assert!(matches!(
            error,
            ServiceError::Network(NetworkError::NoCompatibleRelease)
        ));
        assert!(events.is_empty());
        assert_eq!(fs::read(&target).unwrap(), old_payload);
        assert_eq!(fs::read(&state.ownership_path).unwrap(), old_ownership);
        assert!(!state.backup_root.exists());
    }

    #[test]
    fn route_state_is_fully_separated() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        let int = paths.route_state(GameRoute::IntSteam);
        let cn = paths.route_state(GameRoute::CnSteam);
        assert_eq!(int.root, paths.routes_root.join("int-steam"));
        assert_eq!(int.ownership_path, int.root.join("installed.json"));
        assert_eq!(int.backup_root, int.root.join("backup"));
        assert_eq!(int.staging_root, int.root.join("staging"));
        assert_eq!(cn.root, paths.routes_root.join("cn-steam"));
        assert_eq!(cn.ownership_path, cn.root.join("installed.json"));
        assert_eq!(cn.backup_root, cn.root.join("backup"));
        assert_eq!(cn.staging_root, cn.root.join("staging"));
        assert!(!int.root.starts_with(&cn.root));
        assert!(!cn.root.starts_with(&int.root));
    }

    #[test]
    fn migrates_legacy_route_state_without_cross_contamination() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        fs::create_dir_all(paths.state_root.join("backup/cn-steam")).unwrap();
        fs::create_dir_all(paths.state_root.join("staging/cn-steam")).unwrap();
        fs::write(paths.state_root.join("installed.json"), b"int").unwrap();
        fs::write(paths.state_root.join("installed-cn-steam.json"), b"cn").unwrap();
        fs::write(paths.state_root.join("backup/int.dat"), b"int-backup").unwrap();
        fs::write(
            paths.state_root.join("backup/cn-steam/cn.dat"),
            b"cn-backup",
        )
        .unwrap();
        fs::write(paths.state_root.join("staging/int.dat"), b"int-stage").unwrap();
        fs::write(
            paths.state_root.join("staging/cn-steam/cn.dat"),
            b"cn-stage",
        )
        .unwrap();

        let report = migrate_legacy_state(&paths).unwrap();
        assert_eq!(report.moved.len(), 6);
        let int = paths.route_state(GameRoute::IntSteam);
        let cn = paths.route_state(GameRoute::CnSteam);
        assert_eq!(fs::read(&int.ownership_path).unwrap(), b"int");
        assert_eq!(
            fs::read(int.backup_root.join("int.dat")).unwrap(),
            b"int-backup"
        );
        assert!(!int.backup_root.join("cn-steam").exists());
        assert_eq!(fs::read(&cn.ownership_path).unwrap(), b"cn");
        assert_eq!(
            fs::read(cn.backup_root.join("cn.dat")).unwrap(),
            b"cn-backup"
        );
        assert_eq!(
            fs::read(cn.staging_root.join("cn.dat")).unwrap(),
            b"cn-stage"
        );
    }

    #[test]
    fn legacy_state_migration_refuses_to_overwrite_new_state() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        fs::create_dir_all(&paths.state_root).unwrap();
        fs::write(paths.state_root.join("installed.json"), b"legacy").unwrap();
        let int = paths.route_state(GameRoute::IntSteam);
        fs::create_dir_all(int.ownership_path.parent().unwrap()).unwrap();
        fs::write(&int.ownership_path, b"new").unwrap();

        let error = migrate_legacy_state(&paths).unwrap_err();
        assert!(matches!(error, ServiceError::StateMigrationConflict { .. }));
        assert_eq!(fs::read(&int.ownership_path).unwrap(), b"new");
        assert_eq!(
            fs::read(paths.state_root.join("installed.json")).unwrap(),
            b"legacy"
        );
    }

    #[test]
    fn legacy_ownership_is_bound_only_after_current_files_are_verified() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        let roots = InstallRoots {
            addressables: temp.path().join("addressables"),
            game_data: temp.path().join("game-data"),
        };
        fs::create_dir_all(&roots.game_data).unwrap();
        fs::write(roots.game_data.join("data.unity3d"), b"original").unwrap();

        let payload = b"patch01";
        let hash = format!("{:x}", Sha256::digest(payload));
        let current = manifest("v1", &hash);
        let state = paths.route_state(GameRoute::IntSteam);
        let stage = state.staging_root.join("game-data/data.unity3d");
        fs::create_dir_all(stage.parent().unwrap()).unwrap();
        fs::write(&stage, payload).unwrap();
        install_patch(
            &current,
            &state.staging_root,
            &roots,
            &state.backup_root,
            &state.ownership_path,
        )
        .unwrap();

        let mut legacy = load_ownership(&state.ownership_path).unwrap().unwrap();
        legacy.root_binding = None;
        write_json_atomic(&state.ownership_path, &legacy).unwrap();
        assert!(
            load_ownership_for_roots(&state.ownership_path, &roots)
                .unwrap()
                .is_none()
        );
        assert!(
            bind_verified_legacy_ownership_for_roots(
                &state.ownership_path,
                &roots,
                &current.game.catalog_hash,
            )
            .unwrap()
        );
        assert!(
            load_ownership_for_roots(&state.ownership_path, &roots)
                .unwrap()
                .is_some()
        );

        legacy.root_binding = None;
        write_json_atomic(&state.ownership_path, &legacy).unwrap();
        fs::write(roots.game_data.join("data.unity3d"), b"changed").unwrap();
        assert!(
            !bind_verified_legacy_ownership_for_roots(
                &state.ownership_path,
                &roots,
                &current.game.catalog_hash,
            )
            .unwrap()
        );
        assert!(
            load_ownership_for_roots(&state.ownership_path, &roots)
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn restored_game_file_is_detected_as_changed_patch_state() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        let roots = InstallRoots {
            addressables: temp.path().join("addressables"),
            game_data: temp.path().join("game-data"),
        };
        fs::create_dir_all(&roots.game_data).unwrap();
        fs::write(roots.game_data.join("data.unity3d"), b"original").unwrap();

        let payload = b"patch01";
        let hash = format!("{:x}", Sha256::digest(payload));
        let current = manifest("v1", &hash);
        let state = paths.route_state(GameRoute::IntSteam);
        let stage = state.staging_root.join("game-data/data.unity3d");
        fs::create_dir_all(stage.parent().unwrap()).unwrap();
        fs::write(&stage, payload).unwrap();
        install_patch(
            &current,
            &state.staging_root,
            &roots,
            &state.backup_root,
            &state.ownership_path,
        )
        .unwrap();

        fs::write(roots.game_data.join("data.unity3d"), b"original").unwrap();
        let ownership = load_ownership(&state.ownership_path).unwrap().unwrap();
        let changed = installed_patch_change_count(&ownership, &roots).unwrap();
        assert_eq!(changed, 1);
    }

    #[test]
    fn reset_patch_state_removes_only_patcher_metadata() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        let state = paths.route_state(GameRoute::IntSteam);
        let game_file = temp.path().join("game/data.unity3d");
        fs::create_dir_all(game_file.parent().unwrap()).unwrap();
        fs::write(&game_file, b"steam-restored-game-data").unwrap();
        fs::create_dir_all(&state.backup_root).unwrap();
        fs::create_dir_all(&state.staging_root).unwrap();
        fs::create_dir_all(state.ownership_path.parent().unwrap()).unwrap();
        fs::write(&state.ownership_path, b"stale ownership").unwrap();
        fs::write(state.backup_root.join("backup.dat"), b"backup").unwrap();
        fs::write(state.staging_root.join("stage.dat"), b"stage").unwrap();

        let report = reset_patch_state(&paths, GameRoute::IntSteam).unwrap();

        assert_eq!(
            report,
            PatchStateResetReport {
                ownership_removed: true,
                backup_removed: true,
                staging_removed: true,
            }
        );
        assert!(!state.ownership_path.exists());
        assert!(!state.backup_root.exists());
        assert!(!state.staging_root.exists());
        assert_eq!(fs::read(&game_file).unwrap(), b"steam-restored-game-data");
    }

    #[test]
    fn modified_replace_target_can_be_repaired_without_backup() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        let roots = InstallRoots {
            addressables: temp.path().join("addressables"),
            game_data: temp.path().join("game-data"),
        };
        fs::create_dir_all(&roots.game_data).unwrap();
        fs::write(roots.game_data.join("data.unity3d"), b"original").unwrap();

        let payload = b"patch01";
        let hash = format!("{:x}", Sha256::digest(payload));
        let first = manifest("v1", &hash);
        let state = paths.route_state(GameRoute::IntSteam);
        let stage = state.staging_root.join("game-data/data.unity3d");
        fs::create_dir_all(stage.parent().unwrap()).unwrap();
        fs::write(&stage, payload).unwrap();
        install_patch(
            &first,
            &state.staging_root,
            &roots,
            &state.backup_root,
            &state.ownership_path,
        )
        .unwrap();
        fs::write(roots.game_data.join("data.unity3d"), b"changed").unwrap();

        install_patch(
            &first,
            &state.staging_root,
            &roots,
            &state.backup_root,
            &state.ownership_path,
        )
        .unwrap();
        assert_eq!(
            fs::read(roots.game_data.join("data.unity3d")).unwrap(),
            payload
        );
        assert!(!state.backup_root.exists());
    }
    #[test]
    fn upgrading_create_to_replace_keeps_required_target() {
        let temp = tempdir().unwrap();
        let paths = PatcherPaths::below(temp.path().join("state"));
        let state = paths.route_state(GameRoute::IntSteam);
        let roots = InstallRoots {
            game_data: temp.path().join("game"),
            addressables: temp.path().join("bundles"),
        };
        let old_hash = format!("{:x}", Sha256::digest(b"patch01"));
        let mut old = manifest("v1", &old_hash);
        old.files[0].operation = "create".into();
        let stage = state.staging_root.join("game-data/data.unity3d");
        fs::create_dir_all(stage.parent().unwrap()).unwrap();
        fs::write(&stage, b"patch01").unwrap();
        install_patch(
            &old,
            &state.staging_root,
            &roots,
            &state.backup_root,
            &state.ownership_path,
        )
        .unwrap();
        let ownership = load_ownership(&state.ownership_path).unwrap().unwrap();
        let next = manifest("v2", &format!("{:x}", Sha256::digest(b"patch02")));
        stage_before_existing_removal(
            &state,
            Some(&ownership),
            &next,
            &roots,
            GameRoute::IntSteam,
            &mut |_| {},
            |staging, _| {
                let staged = staging.join("game-data/data.unity3d");
                fs::create_dir_all(staged.parent().unwrap())?;
                fs::write(staged, b"patch02")?;
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(
            fs::read(roots.game_data.join("data.unity3d")).unwrap(),
            b"patch01"
        );
        install_patch(
            &next,
            &state.staging_root,
            &roots,
            &state.backup_root,
            &state.ownership_path,
        )
        .unwrap();
        assert_eq!(
            fs::read(roots.game_data.join("data.unity3d")).unwrap(),
            b"patch02"
        );
        assert!(!state.backup_root.exists());
    }

    #[test]
    fn removal_union_keeps_fresh_metadata_and_obsolete_targets() {
        let mut current = manifest("v2", &"c".repeat(64));
        let mut previous = manifest("v1", &"d".repeat(64));
        let mut obsolete = previous.files[0].clone();
        obsolete.path = "retired.bin".into();
        previous.files.push(obsolete);
        let temp = tempdir().unwrap();
        let roots = InstallRoots {
            game_data: temp.path().join("game"),
            addressables: temp.path().join("bundles"),
        };
        merge_restore_targets(&mut current, &previous, &roots).unwrap();
        assert_eq!(current.files.len(), 2);
        assert_eq!(current.files[0].sha256, "c".repeat(64));
        assert_eq!(current.files[1].path, "retired.bin");
    }
    #[test]
    fn removal_recognizes_old_added_file_and_preserves_unknown_change() {
        let temp = tempdir().unwrap();
        let roots = InstallRoots {
            game_data: temp.path().join("game"),
            addressables: temp.path().join("bundles"),
        };
        fs::create_dir_all(&roots.game_data).unwrap();
        let path = roots.game_data.join("data.unity3d");
        let mut old = manifest("v1", &format!("{:x}", Sha256::digest(b"patch01")));
        old.files[0].operation = "create".into();
        let mut current = manifest("v2", &format!("{:x}", Sha256::digest(b"patch02")));
        current.files[0].operation = "create".into();
        fs::write(&path, b"patch01").unwrap();
        let mut merged = current.clone();
        merge_restore_targets(&mut merged, &old, &roots).unwrap();
        let report = restore_release_files(&merged, &roots, temp.path()).unwrap();
        assert_eq!(report.removed, 1);
        assert!(!path.exists());
        fs::write(&path, b"unknown").unwrap();
        merge_restore_targets(&mut current, &old, &roots).unwrap();
        let report = restore_release_files(&current, &roots, temp.path()).unwrap();
        assert_eq!(report.removed, 0);
        assert_eq!(report.issues.len(), 1);
        assert_eq!(fs::read(&path).unwrap(), b"unknown");
    }
    #[test]
    fn removal_restores_previous_replace_when_current_release_creates_that_path() {
        let temp = tempdir().unwrap();
        let roots = InstallRoots {
            game_data: temp.path().join("game"),
            addressables: temp.path().join("bundles"),
        };
        fs::create_dir_all(&roots.game_data).unwrap();
        let target = roots.game_data.join("data.unity3d");
        fs::write(&target, b"patch01").unwrap();
        let mut old = manifest("v1", &format!("{:x}", Sha256::digest(b"patch01")));
        old.files[0].source_download_url = Some("https://example.test/original.gz".into());
        old.files[0].source_download_sha256 = Some("a".repeat(64));
        old.files[0].source_download_size = Some(5);
        old.files[0].source_sha256 = Some(format!("{:x}", Sha256::digest(b"original")));
        old.files[0].source_size = Some(8);
        let mut current = manifest("v2", &format!("{:x}", Sha256::digest(b"patch02")));
        current.files[0].operation = "create".into();
        merge_restore_targets(&mut current, &old, &roots).unwrap();
        assert_eq!(current.files[0].operation, "replace");
        let staging = temp.path().join("release-originals");
        fs::create_dir_all(staging.join("game-data")).unwrap();
        fs::write(staging.join("game-data/data.unity3d"), b"original").unwrap();
        let report = restore_release_files(&current, &roots, &staging).unwrap();
        assert_eq!(report.restored, 1);
        assert!(report.issues.is_empty());
        assert_eq!(fs::read(&target).unwrap(), b"original");
    }
}
