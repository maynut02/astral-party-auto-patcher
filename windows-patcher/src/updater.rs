use std::ffi::OsString;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
#[cfg(windows)]
use std::process::Command;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
#[cfg(windows)]
use std::{ffi::OsStr, thread, time::Instant};

use reqwest::Url;
use reqwest::blocking::Client;
use reqwest::header::CACHE_CONTROL;
use semver::Version;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use thiserror::Error;

const INDEX_SCHEMA_VERSION: u32 = 1;
const MAX_INDEX_BYTES: u64 = 64 * 1024;
const MAX_UPDATE_BYTES: u64 = 128 * 1024 * 1024;
const UPDATE_DIR_NAME: &str = "updates";
const OPERATION_PREFIX: &str = "operation-";
const RELEASE_EXE_NAME: &str = "AstralWindowsPatcher.exe";
const UPDATE_HELPER_NAME: &str = "AstralWindowsPatcherUpdateHelper.exe";
const APPLY_UPDATE_ARG: &str = "--apply-update";
const ARG_SEPARATOR: &str = "--";
#[cfg(windows)]
const PROCESS_WAIT_TIMEOUT: Duration = Duration::from_secs(30);
#[cfg(windows)]
const REPLACE_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Error)]
pub enum UpdateError {
    #[error("HTTP error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("invalid semantic version: {0}")]
    Semver(#[from] semver::Error),
    #[error("patcher index response is too large")]
    IndexTooLarge,
    #[error("unsupported patcher index schema: {0}")]
    UnsupportedSchema(u32),
    #[error("invalid patcher index SHA-256")]
    InvalidSha256,
    #[error("invalid patcher index size (must be 1..=128 MiB)")]
    InvalidSize,
    #[error("unexpected patcher download URL: {0}")]
    UnexpectedDownloadUrl(String),
    #[error("patcher index URL must use HTTPS: {0}")]
    InvalidIndexUrl(String),
    #[error("patcher download size mismatch: expected {expected}, actual {actual}")]
    DownloadSizeMismatch { expected: u64, actual: u64 },
    #[error("patcher download SHA-256 mismatch: expected {expected}, actual {actual}")]
    DownloadHashMismatch { expected: String, actual: String },
    #[error("invalid internal updater arguments")]
    InvalidUpdaterArguments,
    #[error("timed out waiting for patcher process {0} to exit")]
    PreviousProcessTimeout(u32),
    #[error("timed out waiting for another updater operation")]
    UpdateBusy,
    #[error("timed out while replacing the installed patcher")]
    ReplaceTimeout,
    #[error("could not restart updated patcher (previous executable restored): {0}")]
    RestartFailed(io::Error),
    #[error("restart failed: {restart}; rollback failed: {rollback}; backup retained at {backup}")]
    RollbackFailed {
        restart: io::Error,
        rollback: Box<UpdateError>,
        backup: PathBuf,
    },
    #[error("automatic updater is only supported on Windows")]
    UnsupportedPlatform,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PatcherIndex {
    pub schema_version: u32,
    pub version: String,
    pub download_url: String,
    pub sha256: String,
    pub size: u64,
}

impl PatcherIndex {
    pub fn validate(&self, release_base_url: &str) -> Result<Version, UpdateError> {
        if self.schema_version != INDEX_SCHEMA_VERSION {
            return Err(UpdateError::UnsupportedSchema(self.schema_version));
        }
        let version = Version::parse(&self.version)?;
        validate_integrity_metadata(self.size, &self.sha256)?;
        let base = release_base_url.trim_end_matches('/');
        // Validate the configured origin as well as the index-provided URL. A
        // matching string alone would otherwise also accept an HTTP mirror.
        if !valid_release_base(base) {
            return Err(UpdateError::UnexpectedDownloadUrl(
                self.download_url.clone(),
            ));
        }
        let expected = format!("{base}/windows-patcher-v{version}/{RELEASE_EXE_NAME}");
        if self.download_url != expected {
            return Err(UpdateError::UnexpectedDownloadUrl(
                self.download_url.clone(),
            ));
        }
        Ok(version)
    }
}

fn valid_release_base(base: &str) -> bool {
    let Ok(url) = Url::parse(base) else {
        return false;
    };
    let components: Vec<_> = url.path().split('/').collect();
    url.scheme() == "https"
        && url.host_str() == Some("github.com")
        && url.port().is_none()
        && url.username().is_empty()
        && url.password().is_none()
        && url.query().is_none()
        && url.fragment().is_none()
        && components.len() == 5
        && components[0].is_empty()
        && components[1..3].iter().all(|part| {
            !part.is_empty()
                && *part != "."
                && *part != ".."
                && part
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"-_.".contains(&b))
        })
        && components[3] == "releases"
        && components[4] == "download"
        && url.as_str() == base
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ApplyUpdatePayload {
    /// Compatibility with 0.x releases which already launched the new EXE.
    LegacyCurrentExecutable,
    Staged {
        version: String,
        expected_size: u64,
        expected_sha256: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApplyUpdateRequest {
    pub previous_pid: u32,
    pub payload: ApplyUpdatePayload,
    pub original_args: Vec<OsString>,
}

fn valid_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn validate_integrity_metadata(size: u64, sha256: &str) -> Result<(), UpdateError> {
    if !valid_sha256(sha256) {
        return Err(UpdateError::InvalidSha256);
    }
    if size == 0 || size > MAX_UPDATE_BYTES {
        return Err(UpdateError::InvalidSize);
    }
    Ok(())
}

fn valid_previous_pid(pid: u32) -> bool {
    pid != 0 && pid != std::process::id()
}

pub fn parse_apply_update_request(
    args: &[OsString],
) -> Result<Option<ApplyUpdateRequest>, UpdateError> {
    if args.first().and_then(|arg| arg.to_str()) != Some(APPLY_UPDATE_ARG) {
        return Ok(None);
    }
    let invalid = || UpdateError::InvalidUpdaterArguments;
    let previous_pid = args
        .get(1)
        .and_then(|arg| arg.to_str())
        .filter(|value| !value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()))
        .and_then(|value| value.parse::<u32>().ok())
        .filter(|pid| valid_previous_pid(*pid))
        .ok_or_else(invalid)?;
    if args.get(2).and_then(|arg| arg.to_str()) == Some(ARG_SEPARATOR) {
        return Ok(Some(ApplyUpdateRequest {
            previous_pid,
            payload: ApplyUpdatePayload::LegacyCurrentExecutable,
            original_args: args[3..].to_vec(),
        }));
    }
    let version = args
        .get(2)
        .and_then(|arg| arg.to_str())
        .filter(|value| Version::parse(value).is_ok())
        .ok_or_else(invalid)?
        .to_owned();
    let expected_size = args
        .get(3)
        .and_then(|arg| arg.to_str())
        .filter(|value| !value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()))
        .and_then(|value| value.parse::<u64>().ok())
        .ok_or_else(invalid)?;
    let expected_sha256 = args
        .get(4)
        .and_then(|arg| arg.to_str())
        .ok_or_else(invalid)?
        .to_owned();
    validate_integrity_metadata(expected_size, &expected_sha256).map_err(|_| invalid())?;
    if args.get(5).and_then(|arg| arg.to_str()) != Some(ARG_SEPARATOR) {
        return Err(invalid());
    }
    Ok(Some(ApplyUpdateRequest {
        previous_pid,
        payload: ApplyUpdatePayload::Staged {
            version,
            expected_size,
            expected_sha256,
        },
        original_args: args[6..].to_vec(),
    }))
}

pub fn check_and_launch_update(
    index_url: &str,
    release_base_url: &str,
    state_root: &Path,
    original_args: &[OsString],
) -> Result<bool, UpdateError> {
    #[cfg(not(windows))]
    {
        let _ = (index_url, release_base_url, state_root, original_args);
        Err(UpdateError::UnsupportedPlatform)
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        use windows_sys::Win32::System::Threading::CREATE_NO_WINDOW;

        let client = Client::builder()
            .user_agent(format!("AstralAutoPatcher/{}", env!("CARGO_PKG_VERSION")))
            .https_only(true)
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(90))
            .build()?;
        let index = fetch_index(&client, index_url)?;
        let latest = index.validate(release_base_url)?;
        if !is_newer(&latest, &Version::parse(env!("CARGO_PKG_VERSION"))?) {
            return Ok(false);
        }

        let mut operation = UpdateOperation::create(state_root)?;
        let download = update_download_path(&operation.path, &index.version)?;
        download_update(&client, &download, &index)?;
        // This executable is trusted current code, never the downloaded image.
        let helper = operation.path.join(UPDATE_HELPER_NAME);
        copy_file_new(&std::env::current_exe()?, &helper)?;
        Command::new(&helper)
            .creation_flags(CREATE_NO_WINDOW)
            .arg(APPLY_UPDATE_ARG)
            .arg(std::process::id().to_string())
            .arg(&index.version)
            .arg(index.size.to_string())
            .arg(&index.sha256)
            .arg(ARG_SEPARATOR)
            .args(original_args)
            .spawn()?;
        // The helper owns this directory once spawn succeeds. Never sweep other
        // operations: another check/helper may still be using any of them.
        operation.retain = true;
        Ok(true)
    }
}

fn is_newer(latest: &Version, current: &Version) -> bool {
    // Build metadata is not a SemVer precedence change.
    latest.cmp_precedence(current).is_gt()
}

fn fetch_index(client: &Client, index_url: &str) -> Result<PatcherIndex, UpdateError> {
    let url = Url::parse(index_url)
        .ok()
        .filter(|url| {
            url.scheme() == "https"
                && url.host_str().is_some()
                && url.username().is_empty()
                && url.password().is_none()
                && url.fragment().is_none()
        })
        .ok_or_else(|| UpdateError::InvalidIndexUrl(index_url.to_owned()))?;
    let response = client
        .get(url)
        .timeout(Duration::from_secs(10))
        .header(CACHE_CONTROL, "no-cache")
        .send()?
        .error_for_status()?;
    if response
        .content_length()
        .is_some_and(|size| size > MAX_INDEX_BYTES)
    {
        return Err(UpdateError::IndexTooLarge);
    }
    read_index(response)
}

fn read_index(reader: impl Read) -> Result<PatcherIndex, UpdateError> {
    let mut raw = Vec::new();
    reader.take(MAX_INDEX_BYTES + 1).read_to_end(&mut raw)?;
    if raw.len() as u64 > MAX_INDEX_BYTES {
        return Err(UpdateError::IndexTooLarge);
    }
    Ok(serde_json::from_slice(&raw)?)
}

/// `root` is the private operation directory (or the old shared updates root
/// when reading a released helper's payload). Parse before forming any path;
/// prerelease/build identifiers may contain only SemVer identifier characters.
fn update_download_path(root: &Path, version: &str) -> Result<PathBuf, UpdateError> {
    let version = Version::parse(version)?;
    Ok(root.join(format!("AstralWindowsPatcher-{version}.download")))
}

fn download_update(client: &Client, path: &Path, index: &PatcherIndex) -> Result<(), UpdateError> {
    validate_integrity_metadata(index.size, &index.sha256)?;
    let response = client.get(&index.download_url).send()?.error_for_status()?;
    if let Some(actual) = response.content_length().filter(|size| *size != index.size) {
        return Err(UpdateError::DownloadSizeMismatch {
            expected: index.size,
            actual,
        });
    }
    let mut file = File::options().write(true).create_new(true).open(path)?;
    copy_verified(response, &mut file, index.size, &index.sha256)?;
    file.sync_all()?;
    Ok(())
}

fn copy_verified(
    reader: impl Read,
    writer: &mut impl Write,
    expected_size: u64,
    expected_sha256: &str,
) -> Result<(), UpdateError> {
    validate_integrity_metadata(expected_size, expected_sha256)?;
    // Reading only expected+1 bounds disk, memory, and work even for chunked
    // responses, missing Content-Length, and files growing during verification.
    let mut reader = reader.take(expected_size + 1);
    let mut hasher = Sha256::new();
    let mut size = 0_u64;
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = match reader.read(&mut buffer) {
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            result => result?,
        };
        if read == 0 {
            break;
        }
        size += read as u64;
        if size > expected_size {
            return Err(UpdateError::DownloadSizeMismatch {
                expected: expected_size,
                actual: size,
            });
        }
        writer.write_all(&buffer[..read])?;
        hasher.update(&buffer[..read]);
    }
    if size != expected_size {
        return Err(UpdateError::DownloadSizeMismatch {
            expected: expected_size,
            actual: size,
        });
    }
    let actual = format!("{:x}", hasher.finalize());
    if actual != expected_sha256 {
        return Err(UpdateError::DownloadHashMismatch {
            expected: expected_sha256.to_owned(),
            actual,
        });
    }
    Ok(())
}

fn open_verified_file(path: &Path, size: u64, sha256: &str) -> Result<File, UpdateError> {
    validate_integrity_metadata(size, sha256)?;
    let mut options = File::options();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        use windows_sys::Win32::Storage::FileSystem::{FILE_SHARE_DELETE, FILE_SHARE_READ};
        // Keep the verified payload unwritable until the atomic rename while
        // permitting MoveFileEx to rename this open file on Windows.
        options.share_mode(FILE_SHARE_READ | FILE_SHARE_DELETE);
    }
    let mut file = options.open(path)?;
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.len() != size {
        return Err(UpdateError::DownloadSizeMismatch {
            expected: size,
            actual: metadata.len(),
        });
    }
    copy_verified(&mut file, &mut io::sink(), size, sha256)?;
    Ok(file)
}

fn copy_file_new(from: &Path, to: &Path) -> Result<(), UpdateError> {
    let mut source = File::open(from)?;
    let mut target = File::options().write(true).create_new(true).open(to)?;
    io::copy(&mut source, &mut target)?;
    target.sync_all()?;
    Ok(())
}

struct UpdateOperation {
    path: PathBuf,
    retain: bool,
}

impl UpdateOperation {
    fn create(state_root: &Path) -> Result<Self, UpdateError> {
        static NEXT_OPERATION: AtomicU64 = AtomicU64::new(0);
        let root = state_root.join(UPDATE_DIR_NAME);
        fs::create_dir_all(&root)?;
        let root = root.canonicalize()?;
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        loop {
            let serial = NEXT_OPERATION.fetch_add(1, Ordering::Relaxed);
            let path = root.join(format!(
                "{OPERATION_PREFIX}{}-{stamp}-{serial}",
                std::process::id()
            ));
            match fs::create_dir(&path) {
                Ok(()) => {
                    return Ok(Self {
                        path,
                        retain: false,
                    });
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error.into()),
            }
        }
    }
}

impl Drop for UpdateOperation {
    fn drop(&mut self) {
        if !self.retain {
            // Only directories exclusively created/owned by this operation.
            let _ = fs::remove_dir_all(&self.path);
        }
    }
}

#[cfg(windows)]
pub fn apply_update_and_restart(
    state_root: &Path,
    request: ApplyUpdateRequest,
) -> Result<(), UpdateError> {
    if !valid_previous_pid(request.previous_pid) {
        return Err(UpdateError::InvalidUpdaterArguments);
    }
    // Public callers may construct a request without the CLI parser.
    if let ApplyUpdatePayload::Staged {
        version,
        expected_size,
        expected_sha256,
    } = &request.payload
    {
        Version::parse(version)?;
        validate_integrity_metadata(*expected_size, expected_sha256)?;
    }
    fs::create_dir_all(state_root)?;
    let state_root = state_root.canonicalize()?;
    let current = std::env::current_exe()?.canonicalize()?;
    let (mut operation, replacement) = match &request.payload {
        ApplyUpdatePayload::LegacyCurrentExecutable => {
            let operation = UpdateOperation::create(&state_root)?;
            let replacement = operation.path.join("legacy.download");
            copy_file_new(&current, &replacement)?;
            (operation, replacement)
        }
        ApplyUpdatePayload::Staged {
            version,
            expected_size,
            expected_sha256,
        } => {
            if let Some(root) = helper_operation_root(&state_root, &current)? {
                // The running helper keeps this directory alive. Do not try to
                // recursively remove it while its image is mapped by Windows.
                let path = update_download_path(&root, version)?;
                (
                    UpdateOperation {
                        path: root,
                        retain: true,
                    },
                    path,
                )
            } else {
                // Compatibility with the shared staging path of older helpers.
                // Snapshot it into a private directory; never delete that shared
                // source or the old helper, even after a successful update.
                let source = update_download_path(&state_root.join(UPDATE_DIR_NAME), version)?;
                let mut source = open_verified_file(&source, *expected_size, expected_sha256)?;
                use std::io::{Seek, SeekFrom};
                source.seek(SeekFrom::Start(0))?;
                let operation = UpdateOperation::create(&state_root)?;
                let replacement = update_download_path(&operation.path, version)?;
                let mut target = File::options()
                    .write(true)
                    .create_new(true)
                    .open(&replacement)?;
                copy_verified(&mut source, &mut target, *expected_size, expected_sha256)?;
                target.sync_all()?;
                (operation, replacement)
            }
        }
    };
    // Validate before waiting as well: bad payloads must never touch installed
    // bytes. Keep the final verified handle open through replacement and spawn.
    let verified = match &request.payload {
        ApplyUpdatePayload::Staged {
            expected_size,
            expected_sha256,
            ..
        } => Some(open_verified_file(
            &replacement,
            *expected_size,
            expected_sha256,
        )?),
        ApplyUpdatePayload::LegacyCurrentExecutable => None,
    };
    wait_for_previous_process(request.previous_pid, PROCESS_WAIT_TIMEOUT)?;
    let _lock = acquire_update_lock(&state_root, PROCESS_WAIT_TIMEOUT)?;

    // Keep this independent of the optional terminal/registration module so a
    // headless helper build can apply updates as well. Matches registration.rs.
    let installed = state_root.join("AstralAutoPatcher.exe");
    let backup = operation.path.join("previous.exe");
    let had_installed = match fs::metadata(&installed) {
        Ok(_) => {
            copy_file_new(&installed, &backup)?;
            true
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => false,
        Err(error) => return Err(error.into()),
    };
    // Preserve the backup if replacement/rollback ever fails or is interrupted.
    operation.retain = true;
    atomic_replace(&replacement, &installed)?;
    if let Err(restart) = restart_patcher(&installed, &request.original_args) {
        drop(verified);
        let rollback = if had_installed {
            atomic_replace(&backup, &installed)
        } else {
            fs::remove_file(&installed).map_err(UpdateError::from)
        };
        if let Err(rollback) = rollback {
            return Err(UpdateError::RollbackFailed {
                restart,
                rollback: Box::new(rollback),
                backup,
            });
        }
        return Err(UpdateError::RestartFailed(restart));
    }
    drop(verified);
    // Success-scoped cleanup only. The helper image itself remains until a
    // future explicit cleanup; no process ever sweeps another operation.
    if had_installed {
        let _ = fs::remove_file(&backup);
    }
    let _ = fs::remove_dir(&operation.path);
    Ok(())
}

#[cfg(windows)]
fn helper_operation_root(
    state_root: &Path,
    current: &Path,
) -> Result<Option<PathBuf>, UpdateError> {
    if current.file_name() != Some(OsStr::new(UPDATE_HELPER_NAME)) {
        return Ok(None);
    }
    let Some(parent) = current.parent() else {
        return Ok(None);
    };
    let updates = state_root.join(UPDATE_DIR_NAME).canonicalize()?;
    let is_operation = parent.parent() == Some(updates.as_path())
        && parent
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.starts_with(OPERATION_PREFIX));
    Ok(is_operation.then(|| parent.to_path_buf()))
}

#[cfg(windows)]
fn acquire_update_lock(state_root: &Path, timeout: Duration) -> Result<File, UpdateError> {
    use std::os::windows::fs::OpenOptionsExt;
    use windows_sys::Win32::Foundation::{ERROR_LOCK_VIOLATION, ERROR_SHARING_VIOLATION};
    let deadline = Instant::now() + timeout;
    loop {
        // This persistent file is never unlinked. The exclusive HANDLE, rather
        // than existence of a pathname, serializes concurrent replacements.
        match File::options()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .share_mode(0)
            .open(state_root.join("updater.lock"))
        {
            Ok(file) => return Ok(file),
            Err(error)
                if matches!(error.raw_os_error(), Some(code)
                if code == ERROR_SHARING_VIOLATION as i32 || code == ERROR_LOCK_VIOLATION as i32) =>
            {
                if Instant::now() >= deadline {
                    return Err(UpdateError::UpdateBusy);
                }
                thread::sleep(Duration::from_millis(50));
            }
            Err(error) => return Err(error.into()),
        }
    }
}

#[cfg(windows)]
fn atomic_replace(from: &Path, to: &Path) -> Result<(), UpdateError> {
    use windows_sys::Win32::Foundation::{
        ERROR_ACCESS_DENIED, ERROR_LOCK_VIOLATION, ERROR_SHARING_VIOLATION,
    };
    use windows_sys::Win32::Storage::FileSystem::{
        MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH, MoveFileExW,
    };
    let from = wide_null(from.as_os_str())?;
    let to = wide_null(to.as_os_str())?;
    let deadline = Instant::now() + REPLACE_TIMEOUT;
    loop {
        // No delete-then-rename window, COPY_ALLOWED, or reboot-delayed move.
        if unsafe {
            MoveFileExW(
                from.as_ptr(),
                to.as_ptr(),
                MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
            )
        } != 0
        {
            return Ok(());
        }
        let error = io::Error::last_os_error();
        if !matches!(error.raw_os_error(), Some(code)
            if code == ERROR_ACCESS_DENIED as i32 || code == ERROR_SHARING_VIOLATION as i32 || code == ERROR_LOCK_VIOLATION as i32)
        {
            return Err(error.into());
        }
        if Instant::now() >= deadline {
            return Err(UpdateError::ReplaceTimeout);
        }
        thread::sleep(Duration::from_millis(50));
    }
}

#[cfg(windows)]
fn wait_for_previous_process(pid: u32, timeout: Duration) -> Result<(), UpdateError> {
    use windows_sys::Win32::Foundation::{
        ERROR_INVALID_PARAMETER, WAIT_FAILED, WAIT_OBJECT_0, WAIT_TIMEOUT,
    };
    use windows_sys::Win32::System::Threading::{
        OpenProcess, PROCESS_SYNCHRONIZE, WaitForSingleObject,
    };
    if !valid_previous_pid(pid) {
        return Err(UpdateError::InvalidUpdaterArguments);
    }
    let raw = unsafe { OpenProcess(PROCESS_SYNCHRONIZE, 0, pid) };
    if raw.is_null() {
        let error = io::Error::last_os_error();
        // The parent can exit between spawn and OpenProcess. Other failures
        // (especially access denied) are not proof of process termination.
        return if error.raw_os_error() == Some(ERROR_INVALID_PARAMETER as i32) {
            Ok(())
        } else {
            Err(error.into())
        };
    }
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    // OpenProcess returns NULL on failure, not INVALID_HANDLE_VALUE. OwnedHandle
    // closes the successful, non-inheritable handle on every return path.
    let handle = unsafe { OwnedHandle::from_raw_handle(raw) };
    let result = unsafe {
        WaitForSingleObject(
            handle.as_raw_handle(),
            timeout.as_millis().min(u32::MAX as u128 - 1) as u32,
        )
    };
    match result {
        WAIT_OBJECT_0 => Ok(()),
        WAIT_TIMEOUT => Err(UpdateError::PreviousProcessTimeout(pid)),
        WAIT_FAILED => Err(io::Error::last_os_error().into()),
        _ => Err(io::Error::other("unexpected process wait result").into()),
    }
}

#[cfg(windows)]
pub(crate) fn restart_patcher(installed: &Path, args: &[OsString]) -> io::Result<()> {
    use std::os::windows::process::CommandExt;
    use windows_sys::Win32::System::Threading::CREATE_NEW_CONSOLE;
    let mut command = Command::new(installed);
    command.creation_flags(CREATE_NEW_CONSOLE).args(args);
    // A hidden helper can inherit redirected/stale handles. Clear the helper's
    // standard slots just during spawn so Rust omits STARTF_USESTDHANDLES and
    // Windows supplies the restarted TUI's new console handles.
    let _stdio = console_handles::Cleared::new()?;
    command.spawn()?;
    Ok(())
}

#[cfg(windows)]
mod console_handles {
    use std::io;
    use windows_sys::Win32::Foundation::{HANDLE, INVALID_HANDLE_VALUE};

    // These two kernel32 exports do not require enabling another windows-sys
    // feature for the otherwise console-independent updater helper.
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetStdHandle(kind: u32) -> HANDLE;
        fn SetStdHandle(kind: u32, handle: HANDLE) -> i32;
    }
    const KINDS: [u32; 3] = [(-10_i32) as u32, (-11_i32) as u32, (-12_i32) as u32];

    pub(super) struct Cleared(Vec<(u32, HANDLE)>);

    impl Cleared {
        pub(super) fn new() -> io::Result<Self> {
            let mut saved = Self(Vec::new());
            for kind in KINDS {
                let handle = unsafe { GetStdHandle(kind) };
                if handle == INVALID_HANDLE_VALUE {
                    return Err(io::Error::last_os_error());
                }
                if unsafe { SetStdHandle(kind, std::ptr::null_mut()) } == 0 {
                    return Err(io::Error::last_os_error());
                }
                saved.0.push((kind, handle));
            }
            Ok(saved)
        }
    }

    impl Drop for Cleared {
        fn drop(&mut self) {
            for &(kind, handle) in &self.0 {
                unsafe {
                    SetStdHandle(kind, handle);
                }
            }
        }
    }
}

#[cfg(windows)]
fn wide_null(value: &OsStr) -> Result<Vec<u16>, UpdateError> {
    use std::os::windows::ffi::OsStrExt;
    let mut value: Vec<_> = value.encode_wide().collect();
    if value.contains(&0) {
        return Err(UpdateError::InvalidUpdaterArguments);
    }
    value.push(0);
    Ok(value)
}

#[cfg(not(windows))]
pub fn apply_update_and_restart(
    _state_root: &Path,
    _request: ApplyUpdateRequest,
) -> Result<(), UpdateError> {
    Err(UpdateError::UnsupportedPlatform)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::io::Cursor;

    const RELEASE_BASE: &str = "https://github.com/example/repo/releases/download";
    const OTHER_PID: u32 = u32::MAX - 1;

    fn digest(bytes: &[u8]) -> String {
        format!("{:x}", Sha256::digest(bytes))
    }

    fn index(version: &str) -> PatcherIndex {
        PatcherIndex {
            schema_version: 1,
            version: version.to_owned(),
            download_url: format!("{RELEASE_BASE}/windows-patcher-v{version}/{RELEASE_EXE_NAME}"),
            sha256: digest(b"abc"),
            size: 3,
        }
    }

    fn staged_args() -> Vec<OsString> {
        [
            APPLY_UPDATE_ARG.to_owned(),
            OTHER_PID.to_string(),
            "2.0.0".to_owned(),
            "3".to_owned(),
            digest(b"abc"),
            ARG_SEPARATOR.to_owned(),
        ]
        .into_iter()
        .map(OsString::from)
        .collect()
    }

    #[test]
    fn validates_schema_one_and_exact_github_asset() {
        assert_eq!(
            index("1.3.1").validate(RELEASE_BASE).unwrap(),
            Version::new(1, 3, 1)
        );
        assert!(index("1.3.1").validate(&format!("{RELEASE_BASE}/")).is_ok());
        let mut invalid = index("1.3.1");
        invalid.schema_version = 2;
        assert!(matches!(
            invalid.validate(RELEASE_BASE),
            Err(UpdateError::UnsupportedSchema(2))
        ));
    }

    #[test]
    fn rejects_asset_substitutions_and_insecure_or_unexpected_origins() {
        let valid = index("1.3.1");
        for url in [
            valid.download_url.replace("https://", "http://"),
            valid
                .download_url
                .replace("github.com", "github.com.evil.test"),
            valid.download_url.replace("example/repo", "other/repo"),
            valid.download_url.replace("windows-patcher-v", "v"),
            format!("{}?download=1", valid.download_url),
            format!("{}#fragment", valid.download_url),
            valid
                .download_url
                .replace("AstralWindowsPatcher.exe", "other.exe"),
        ] {
            let mut bad = valid.clone();
            bad.download_url = url;
            assert!(matches!(
                bad.validate(RELEASE_BASE),
                Err(UpdateError::UnexpectedDownloadUrl(_))
            ));
        }
        // Even a perfectly matching URL must not legitimize an unsafe base.
        for base in [
            RELEASE_BASE.replace("https://", "http://"),
            RELEASE_BASE.replace("github.com", "example.test"),
            RELEASE_BASE.replace("github.com", "user@github.com"),
            RELEASE_BASE.replace("github.com", "github.com:444"),
            RELEASE_BASE.replace("example", "%65xample"),
            format!("{RELEASE_BASE}?query"),
            format!("{RELEASE_BASE}/../download"),
        ] {
            let mut bad = valid.clone();
            bad.download_url = format!("{base}/windows-patcher-v1.3.1/{RELEASE_EXE_NAME}");
            assert!(matches!(
                bad.validate(&base),
                Err(UpdateError::UnexpectedDownloadUrl(_))
            ));
        }
    }

    #[test]
    fn validates_download_size_bounds_and_lowercase_sha256() {
        for size in [0, MAX_UPDATE_BYTES + 1, u64::MAX] {
            let mut bad = index("1.3.1");
            bad.size = size;
            assert!(matches!(
                bad.validate(RELEASE_BASE),
                Err(UpdateError::InvalidSize)
            ));
        }
        let mut maximum = index("1.3.1");
        maximum.size = MAX_UPDATE_BYTES;
        assert!(maximum.validate(RELEASE_BASE).is_ok());
        for hash in [
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
        ] {
            let mut bad = index("1.3.1");
            bad.sha256 = hash;
            assert!(matches!(
                bad.validate(RELEASE_BASE),
                Err(UpdateError::InvalidSha256)
            ));
        }
    }

    #[test]
    fn semver_precedence_rejects_downgrades_equal_versions_and_build_only_changes() {
        let current = Version::parse("1.3.1+build.9").unwrap();
        for value in ["0.8.10", "1.3.0", "1.3.1", "1.3.1-rc.9", "1.3.1+build.10"] {
            assert!(
                !is_newer(&Version::parse(value).unwrap(), &current),
                "{value}"
            );
        }
        for value in ["1.3.2-alpha.1", "1.3.2", "1.10.0", "2.0.0"] {
            assert!(
                is_newer(&Version::parse(value).unwrap(), &current),
                "{value}"
            );
        }
        assert!(is_newer(
            &Version::new(1, 3, 1),
            &Version::parse("1.3.1-rc.1").unwrap()
        ));
    }

    #[test]
    fn rejects_version_path_escapes_even_inside_prerelease_and_build_metadata() {
        let root = Path::new("private-operation");
        for version in [
            "../1.3.1",
            "1.3.1-../../escape",
            "1.3.1-rc/escape",
            "1.3.1+build\\escape",
            "1.3.1:stream",
            "1.3.1\0",
            "01.3.1",
            "v1.3.1",
        ] {
            assert!(update_download_path(root, version).is_err(), "{version:?}");
            assert!(
                index(version).validate(RELEASE_BASE).is_err(),
                "{version:?}"
            );
            let mut args = staged_args();
            args[2] = version.into();
            assert!(matches!(
                parse_apply_update_request(&args),
                Err(UpdateError::InvalidUpdaterArguments)
            ));
        }
        let path = update_download_path(root, "1.3.2-rc.1+build.2").unwrap();
        assert_eq!(path.parent(), Some(root));
        assert_eq!(path.extension().unwrap(), "download");
        assert!(index("1.3.2-rc.1+build.2").validate(RELEASE_BASE).is_ok());
    }

    #[test]
    fn parses_staged_request_preserving_every_resume_argument() {
        let original: Vec<OsString> = [
            "astral://install?file=한글%20%22&x=1",
            "",
            "--",
            "a b",
            "quote\"inside",
            "trailing\\",
            "& | < > ^ % $ `",
        ]
        .into_iter()
        .map(OsString::from)
        .collect();
        let mut args = staged_args();
        args.extend(original.clone());
        let request = parse_apply_update_request(&args).unwrap().unwrap();
        assert_eq!(request.previous_pid, OTHER_PID);
        assert_eq!(
            request.payload,
            ApplyUpdatePayload::Staged {
                version: "2.0.0".into(),
                expected_size: 3,
                expected_sha256: digest(b"abc"),
            }
        );
        assert_eq!(request.original_args, original);
        assert!(
            parse_apply_update_request(&staged_args())
                .unwrap()
                .unwrap()
                .original_args
                .is_empty()
        );
    }

    #[cfg(windows)]
    #[test]
    fn resume_arguments_preserve_non_unicode_windows_strings() {
        use std::os::windows::ffi::OsStringExt;
        let original = OsString::from_wide(&[0xd800, b' ' as u16, 0xdcff]);
        let mut args = staged_args();
        args.push(original.clone());
        assert_eq!(
            parse_apply_update_request(&args)
                .unwrap()
                .unwrap()
                .original_args,
            vec![original]
        );
    }

    #[test]
    fn accepts_legacy_0_x_handoff_and_empty_resume_arguments() {
        let mut args = vec![
            APPLY_UPDATE_ARG.into(),
            OTHER_PID.to_string().into(),
            ARG_SEPARATOR.into(),
        ];
        let empty = parse_apply_update_request(&args).unwrap().unwrap();
        assert_eq!(empty.payload, ApplyUpdatePayload::LegacyCurrentExecutable);
        assert!(empty.original_args.is_empty());
        args.push("astral://settings?a=한글&x=%22".into());
        let request = parse_apply_update_request(&args).unwrap().unwrap();
        assert_eq!(request.original_args, args[3..]);
    }

    #[test]
    fn rejects_zero_self_signed_and_overflowing_pids() {
        for pid in [
            "0".to_owned(),
            std::process::id().to_string(),
            "-1".into(),
            "+1".into(),
            "4294967296".into(),
            " 12".into(),
            "".into(),
        ] {
            let mut args = staged_args();
            args[1] = pid.clone().into();
            assert!(
                matches!(
                    parse_apply_update_request(&args),
                    Err(UpdateError::InvalidUpdaterArguments)
                ),
                "{pid}"
            );
            args.truncate(2);
            args.push(ARG_SEPARATOR.into());
            assert!(
                matches!(
                    parse_apply_update_request(&args),
                    Err(UpdateError::InvalidUpdaterArguments)
                ),
                "{pid}"
            );
        }
    }

    #[test]
    fn rejects_missing_separator_truncated_requests_and_invalid_integrity_metadata() {
        let args = staged_args();
        for count in 1..args.len() {
            assert!(matches!(
                parse_apply_update_request(&args[..count]),
                Err(UpdateError::InvalidUpdaterArguments)
            ));
        }
        for (position, value) in [
            (3, "0".to_owned()),
            (3, (MAX_UPDATE_BYTES + 1).to_string()),
            (3, "+3".into()),
            (3, u128::MAX.to_string()),
            (4, "ABC".into()),
            (5, "--other".into()),
        ] {
            let mut bad = args.clone();
            bad[position] = value.into();
            assert!(matches!(
                parse_apply_update_request(&bad),
                Err(UpdateError::InvalidUpdaterArguments)
            ));
        }
        assert!(parse_apply_update_request(&[]).unwrap().is_none());
        assert!(
            parse_apply_update_request(&["astral://settings".into()])
                .unwrap()
                .is_none()
        );
    }

    fn index_json() -> Vec<u8> {
        format!(r#"{{"schemaVersion":1,"version":"1.3.1","downloadUrl":"{RELEASE_BASE}/windows-patcher-v1.3.1/{RELEASE_EXE_NAME}","sha256":"{}","size":3}}"#, digest(b"abc")).into_bytes()
    }

    #[test]
    fn reads_bounded_index_with_unknown_length_and_rejects_truncation() {
        let raw = index_json();
        assert_eq!(read_index(Cursor::new(&raw)).unwrap(), index("1.3.1"));
        assert!(matches!(
            read_index(Cursor::new(&raw[..raw.len() - 1])),
            Err(UpdateError::Json(_))
        ));
        let mut exact_limit = raw;
        exact_limit.resize(MAX_INDEX_BYTES as usize, b' ');
        assert!(read_index(Cursor::new(&exact_limit)).is_ok());
        exact_limit.push(b' ');
        assert!(matches!(
            read_index(Cursor::new(exact_limit)),
            Err(UpdateError::IndexTooLarge)
        ));
    }

    struct CountReads<'a, R> {
        inner: R,
        count: &'a Cell<usize>,
    }

    impl<R: Read> Read for CountReads<'_, R> {
        fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
            let size = self.inner.read(out)?;
            self.count.set(self.count.get() + size);
            Ok(size)
        }
    }

    #[test]
    fn oversized_index_stream_is_read_only_to_limit_plus_one() {
        let count = Cell::new(0);
        let reader = CountReads {
            inner: io::repeat(b' '),
            count: &count,
        };
        assert!(matches!(
            read_index(reader),
            Err(UpdateError::IndexTooLarge)
        ));
        assert_eq!(count.get(), MAX_INDEX_BYTES as usize + 1);
    }

    #[test]
    fn validates_bytes_and_detects_tampering_and_truncated_downloads() {
        let mut output = Vec::new();
        copy_verified(&b"abc"[..], &mut output, 3, &digest(b"abc")).unwrap();
        assert_eq!(output, b"abc");
        assert!(matches!(
            copy_verified(&b"ab"[..], &mut io::sink(), 3, &digest(b"abc")),
            Err(UpdateError::DownloadSizeMismatch {
                expected: 3,
                actual: 2
            })
        ));
        assert!(matches!(
            copy_verified(&b"xyz"[..], &mut io::sink(), 3, &digest(b"abc")),
            Err(UpdateError::DownloadHashMismatch { .. })
        ));
        assert!(matches!(
            copy_verified(&b"abcd"[..], &mut io::sink(), 3, &digest(b"abc")),
            Err(UpdateError::DownloadSizeMismatch {
                expected: 3,
                actual: 4
            })
        ));
    }

    #[test]
    fn bounds_payload_reads_and_never_writes_beyond_expected_size() {
        let count = Cell::new(0);
        let reader = CountReads {
            inner: io::repeat(b'x'),
            count: &count,
        };
        let mut output = Vec::new();
        assert!(matches!(
            copy_verified(reader, &mut output, 7, &digest(b"xxxxxxx")),
            Err(UpdateError::DownloadSizeMismatch {
                expected: 7,
                actual: 8
            })
        ));
        assert_eq!(count.get(), 8);
        assert!(output.len() <= 7);
        let count = Cell::new(0);
        let reader = CountReads {
            inner: io::repeat(b'x'),
            count: &count,
        };
        assert!(matches!(
            copy_verified(reader, &mut io::sink(), MAX_UPDATE_BYTES + 1, &digest(b"x")),
            Err(UpdateError::InvalidSize)
        ));
        assert_eq!(count.get(), 0);
    }

    struct FaultReader {
        bytes: Cursor<&'static [u8]>,
        interrupt_once: bool,
        fail_at_eof: bool,
    }

    impl Read for FaultReader {
        fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
            if self.interrupt_once {
                self.interrupt_once = false;
                return Err(io::ErrorKind::Interrupted.into());
            }
            // Short network reads must not be mistaken for EOF.
            let count = output.len().min(1);
            let read = self.bytes.read(&mut output[..count])?;
            if read == 0 && self.fail_at_eof {
                return Err(io::ErrorKind::UnexpectedEof.into());
            }
            Ok(read)
        }
    }

    #[test]
    fn tolerates_interrupted_and_short_reads_but_requires_a_clean_eof() {
        let mut output = Vec::new();
        let reader = FaultReader {
            bytes: Cursor::new(b"abc"),
            interrupt_once: true,
            fail_at_eof: false,
        };
        copy_verified(reader, &mut output, 3, &digest(b"abc")).unwrap();
        assert_eq!(output, b"abc");
        let reader = FaultReader {
            bytes: Cursor::new(b"abc"),
            interrupt_once: false,
            fail_at_eof: true,
        };
        // A transport error is still a failure even if all declared bytes and
        // the expected digest were received before the error was reported.
        assert!(
            matches!(copy_verified(reader, &mut io::sink(), 3, &digest(b"abc")),
            Err(UpdateError::Io(error)) if error.kind() == io::ErrorKind::UnexpectedEof)
        );
        let reader = FaultReader {
            bytes: Cursor::new(b"ab"),
            interrupt_once: false,
            fail_at_eof: true,
        };
        assert!(matches!(
            copy_verified(reader, &mut io::sink(), 3, &digest(b"abc")),
            Err(UpdateError::Io(_))
        ));
    }

    #[test]
    fn fails_on_partial_disk_writes_instead_of_accepting_a_matching_input_digest() {
        struct FailingWriter(usize);
        impl Write for FailingWriter {
            fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
                if self.0 == 0 {
                    return Err(io::Error::other("injected disk failure"));
                }
                let count = bytes.len().min(self.0);
                self.0 -= count;
                Ok(count)
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        assert!(matches!(
            copy_verified(&b"abc"[..], &mut FailingWriter(2), 3, &digest(b"abc")),
            Err(UpdateError::Io(_))
        ));
    }

    #[test]
    fn file_verification_detects_same_size_corruption_and_wrong_size() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("payload.download");
        fs::write(&path, b"abc").unwrap();
        drop(open_verified_file(&path, 3, &digest(b"abc")).unwrap());
        fs::write(&path, b"xyz").unwrap();
        assert!(matches!(
            open_verified_file(&path, 3, &digest(b"abc")),
            Err(UpdateError::DownloadHashMismatch { .. })
        ));
        fs::write(&path, b"ab").unwrap();
        assert!(matches!(
            open_verified_file(&path, 3, &digest(b"abc")),
            Err(UpdateError::DownloadSizeMismatch {
                expected: 3,
                actual: 2
            })
        ));
    }

    #[test]
    fn operation_cleanup_is_private_and_does_not_delete_other_active_work() {
        let temp = tempfile::tempdir().unwrap();
        let first = UpdateOperation::create(temp.path()).unwrap();
        let second = UpdateOperation::create(temp.path()).unwrap();
        assert_ne!(first.path, second.path);
        fs::write(first.path.join("active.download"), b"keep").unwrap();
        fs::write(second.path.join("failed.download"), b"remove").unwrap();
        let second_path = second.path.clone();
        drop(second);
        assert!(!second_path.exists());
        assert_eq!(
            fs::read(first.path.join("active.download")).unwrap(),
            b"keep"
        );
        let mut handed_off = UpdateOperation::create(temp.path()).unwrap();
        let handed_off_path = handed_off.path.clone();
        handed_off.retain = true;
        drop(handed_off);
        assert!(handed_off_path.is_dir());
    }

    #[test]
    fn helper_copy_never_overwrites_an_existing_operation_file() {
        let temp = tempfile::tempdir().unwrap();
        let source = temp.path().join("trusted.exe");
        let target = temp.path().join("helper.exe");
        fs::write(&source, b"trusted").unwrap();
        fs::write(&target, b"other-operation").unwrap();
        assert!(copy_file_new(&source, &target).is_err());
        assert_eq!(fs::read(&target).unwrap(), b"other-operation");
    }

    #[cfg(windows)]
    #[test]
    fn verified_handle_denies_payload_writes_until_closed() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("payload.download");
        fs::write(&path, b"abc").unwrap();
        let verified = open_verified_file(&path, 3, &digest(b"abc")).unwrap();
        assert!(File::options().write(true).open(&path).is_err());
        drop(verified);
        assert!(File::options().write(true).open(&path).is_ok());
    }

    #[cfg(windows)]
    #[test]
    fn update_lock_is_exclusive_and_survives_release_without_path_deletion() {
        let temp = tempfile::tempdir().unwrap();
        let first = acquire_update_lock(temp.path(), Duration::ZERO).unwrap();
        assert!(matches!(
            acquire_update_lock(temp.path(), Duration::ZERO),
            Err(UpdateError::UpdateBusy)
        ));
        drop(first);
        assert!(temp.path().join("updater.lock").is_file());
        drop(acquire_update_lock(temp.path(), Duration::ZERO).unwrap());
    }

    #[cfg(windows)]
    #[test]
    fn atomic_replacement_retains_the_source_when_move_fails() {
        let temp = tempfile::tempdir().unwrap();
        let old = temp.path().join("installed.exe");
        let new = temp.path().join("replacement.download");
        fs::write(&old, b"old").unwrap();
        fs::write(&new, b"new").unwrap();
        assert!(atomic_replace(&new, &temp.path().join("missing/installed.exe")).is_err());
        assert_eq!(fs::read(&old).unwrap(), b"old");
        assert_eq!(fs::read(&new).unwrap(), b"new");
        atomic_replace(&new, &old).unwrap();
        assert_eq!(fs::read(&old).unwrap(), b"new");
        assert!(!new.exists());
    }

    #[cfg(windows)]
    #[test]
    fn rejects_invalid_pids_for_direct_apply_calls_without_creating_state() {
        let temp = tempfile::tempdir().unwrap();
        let state = temp.path().join("state");
        for pid in [0, std::process::id()] {
            assert!(matches!(
                wait_for_previous_process(pid, Duration::ZERO),
                Err(UpdateError::InvalidUpdaterArguments)
            ));
            assert!(matches!(
                apply_update_and_restart(
                    &state,
                    ApplyUpdateRequest {
                        previous_pid: pid,
                        payload: ApplyUpdatePayload::LegacyCurrentExecutable,
                        original_args: vec![],
                    }
                ),
                Err(UpdateError::InvalidUpdaterArguments)
            ));
        }
        assert!(!state.exists());
    }

    #[cfg(windows)]
    #[test]
    fn recognizes_private_helper_location_and_keeps_old_shared_location_compatible() {
        let temp = tempfile::tempdir().unwrap();
        let state = temp.path().canonicalize().unwrap();
        let operation = UpdateOperation::create(&state).unwrap();
        let helper = operation.path.join(UPDATE_HELPER_NAME);
        fs::write(&helper, b"fixture").unwrap();
        assert_eq!(
            helper_operation_root(&state, &helper).unwrap(),
            Some(operation.path.clone())
        );
        let old_helper = state.join(UPDATE_DIR_NAME).join(UPDATE_HELPER_NAME);
        fs::write(&old_helper, b"fixture").unwrap();
        assert_eq!(helper_operation_root(&state, &old_helper).unwrap(), None);
    }

    #[cfg(windows)]
    #[test]
    fn previous_process_wait_fixture() {
        // Only the isolated test-harness child below sets this flag. This is
        // never the actual patcher binary and cannot enter CLI/registration.
        if std::env::var_os("ASTRAL_UPDATER_WAIT_TEST_CHILD").is_some() {
            thread::sleep(Duration::from_millis(500));
        }
    }

    #[cfg(windows)]
    #[test]
    fn process_wait_timeout_fails_until_the_actual_process_terminates() {
        use std::os::windows::process::CommandExt;
        use std::process::Stdio;
        use windows_sys::Win32::System::Threading::CREATE_NO_WINDOW;
        let mut child = Command::new(std::env::current_exe().unwrap())
            .creation_flags(CREATE_NO_WINDOW)
            .args(["--exact", "updater::tests::previous_process_wait_fixture"])
            .env("ASTRAL_UPDATER_WAIT_TEST_CHILD", "1")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let timed_out = wait_for_previous_process(child.id(), Duration::ZERO);
        let still_running = child.try_wait().unwrap().is_none();
        let terminated = wait_for_previous_process(child.id(), Duration::from_secs(5));
        let exit = child.wait().unwrap();
        assert!(
            matches!(timed_out, Err(UpdateError::PreviousProcessTimeout(pid)) if pid == child.id())
        );
        assert!(still_running);
        terminated.unwrap();
        assert!(exit.success());
    }
}
