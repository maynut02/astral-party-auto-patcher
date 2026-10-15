#[cfg(windows)]
use std::fs;
use std::path::{Path, PathBuf};

use thiserror::Error;

pub const APP_NAME: &str = "AstralAutoPatcher";
pub const EXE_NAME: &str = "AstralAutoPatcher.exe";
pub const PROTOCOL_SCHEME: &str = crate::uri::URI_SCHEME;

#[derive(Debug, Error)]
pub enum RegistrationError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Install(#[from] crate::install::InstallError),
}

pub fn installed_exe_path(state_root: &Path) -> PathBuf {
    state_root.join(EXE_NAME)
}

pub fn protocol_command(exe: &Path) -> String {
    format!("\"{}\" \"%1\"", exe.display())
}

#[cfg(windows)]
fn same_file(left: &Path, right: &Path) -> bool {
    let Ok(left) = fs::canonicalize(left) else {
        return false;
    };
    let Ok(right) = fs::canonicalize(right) else {
        return false;
    };
    left.to_string_lossy()
        .eq_ignore_ascii_case(&right.to_string_lossy())
}

/// An old copy in Downloads must not overwrite a newer, already installed patcher.
#[cfg(windows)]
pub fn launch_newer_installed(
    state_root: &Path,
    args: &[std::ffi::OsString],
) -> Result<bool, RegistrationError> {
    let current = std::env::current_exe()?;
    let installed = installed_exe_path(state_root);
    if !same_file(&current, &installed) && installed_is_newer(&installed) {
        crate::updater::restart_patcher(&installed, args)?;
        return Ok(true);
    }
    Ok(false)
}

#[cfg(windows)]
fn installed_is_newer(installed: &Path) -> bool {
    let current =
        semver::Version::parse(env!("CARGO_PKG_VERSION")).expect("Cargo version is valid");
    executable_version(installed).is_some_and(|version| version > current)
}

#[cfg(windows)]
fn executable_version(path: &Path) -> Option<semver::Version> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        GetFileVersionInfoSizeW, GetFileVersionInfoW, VS_FIXEDFILEINFO, VerQueryValueW,
    };

    let wide = path
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect::<Vec<_>>();
    let length = unsafe { GetFileVersionInfoSizeW(wide.as_ptr(), std::ptr::null_mut()) };
    if length == 0 || length > 1024 * 1024 {
        return None;
    }
    let mut bytes = vec![0u8; length as usize];
    if unsafe { GetFileVersionInfoW(wide.as_ptr(), 0, length, bytes.as_mut_ptr().cast()) } == 0 {
        return None;
    }
    let mut info = std::ptr::null_mut();
    let mut size = 0;
    if unsafe {
        VerQueryValueW(
            bytes.as_ptr().cast(),
            [b'\\' as u16, 0].as_ptr(),
            &mut info,
            &mut size,
        )
    } == 0
        || info.is_null()
        || size < std::mem::size_of::<VS_FIXEDFILEINFO>() as u32
    {
        return None;
    }
    // Version buffers need not have Rust's struct alignment.
    let info = unsafe { std::ptr::read_unaligned(info.cast::<VS_FIXEDFILEINFO>()) };
    if info.dwSignature != 0xFEEF04BD {
        return None;
    }
    Some(semver::Version::new(
        u64::from(info.dwFileVersionMS >> 16),
        u64::from(info.dwFileVersionMS & 0xffff),
        u64::from(info.dwFileVersionLS >> 16),
    ))
}

#[cfg(windows)]
pub fn ensure_self_installed_and_registered(
    state_root: &Path,
) -> Result<PathBuf, RegistrationError> {
    use winreg::RegKey;
    use winreg::enums::HKEY_CURRENT_USER;

    fs::create_dir_all(state_root)?;
    let current = std::env::current_exe()?;
    let installed = installed_exe_path(state_root);
    if !same_file(&current, &installed) && !installed_is_newer(&installed) {
        crate::install::atomic_write(&installed, &fs::read(&current)?)?;
    }

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let base = format!("Software\\Classes\\{PROTOCOL_SCHEME}");
    let (protocol, _) = hkcu.create_subkey(&base)?;
    protocol.set_value("", &format!("URL:{APP_NAME} Protocol"))?;
    protocol.set_value("URL Protocol", &"")?;

    let (icon, _) = protocol.create_subkey("DefaultIcon")?;
    icon.set_value("", &format!("\"{}\",0", installed.display()))?;

    let (command, _) = protocol.create_subkey("shell\\open\\command")?;
    command.set_value("", &protocol_command(&installed))?;
    Ok(installed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol_command_quotes_executable_and_uri() {
        let command = protocol_command(Path::new("C:/Users/Test/App Data/AstralAutoPatcher.exe"));
        assert_eq!(
            command,
            "\"C:/Users/Test/App Data/AstralAutoPatcher.exe\" \"%1\""
        );
    }

    #[cfg(all(windows, feature = "terminal"))]
    #[test]
    fn installed_version_guard_reads_windows_resources_and_preserves_newer_builds() {
        let current = semver::Version::parse(env!("CARGO_PKG_VERSION")).unwrap();
        let executable = std::env::current_exe().unwrap();
        assert_eq!(executable_version(&executable), Some(current.clone()));
        let original = fs::read(executable).unwrap();
        let temp = tempfile::tempdir().unwrap();
        for (name, patch, newer) in [
            ("newer.exe", current.patch + 1, true),
            ("same.exe", current.patch, false),
            ("older.exe", current.patch.saturating_sub(1), false),
        ] {
            let mut bytes = original.clone();
            let headers = bytes
                .windows(8)
                .enumerate()
                .filter_map(|(i, b)| {
                    (b == [0xbd, 0x04, 0xef, 0xfe, 0x00, 0x00, 0x01, 0x00]).then_some(i)
                })
                .collect::<Vec<_>>();
            assert!(!headers.is_empty());
            for offset in headers {
                let major_minor = ((current.major as u32) << 16) | current.minor as u32;
                bytes[offset + 8..offset + 12].copy_from_slice(&major_minor.to_le_bytes());
                bytes[offset + 12..offset + 16]
                    .copy_from_slice(&((patch as u32) << 16).to_le_bytes());
            }
            let installed = temp.path().join(name);
            fs::write(&installed, bytes).unwrap();
            assert_eq!(installed_is_newer(&installed), newer, "{name}");
        }
        let invalid = temp.path().join("invalid.exe");
        fs::write(&invalid, b"no executable version resource").unwrap();
        assert_eq!(executable_version(&invalid), None);
        assert!(!installed_is_newer(&invalid));
    }
}
