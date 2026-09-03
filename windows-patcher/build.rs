#[cfg(windows)]
use std::env;
#[cfg(windows)]
use std::fs;
#[cfg(windows)]
use std::io::{self, Write};
#[cfg(windows)]
use std::path::{Path, PathBuf};

#[cfg(windows)]
const ANDROID_ICON_PATHS: &[&str] = &[
    "../android-patcher/app/src/main/res/mipmap-mdpi/ic_launcher.png",
    "../android-patcher/app/src/main/res/mipmap-hdpi/ic_launcher.png",
    "../android-patcher/app/src/main/res/mipmap-xhdpi/ic_launcher.png",
    "../android-patcher/app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
    "../android-patcher/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
];

#[cfg(windows)]
fn png_dimensions(data: &[u8]) -> io::Result<(u32, u32)> {
    const PNG_SIGNATURE: &[u8; 8] = b"\x89PNG\r\n\x1a\n";
    if data.len() < 24 || &data[..8] != PNG_SIGNATURE || &data[12..16] != b"IHDR" {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "launcher icon is not a valid PNG",
        ));
    }
    Ok((
        u32::from_be_bytes(data[16..20].try_into().expect("PNG width slice")),
        u32::from_be_bytes(data[20..24].try_into().expect("PNG height slice")),
    ))
}

#[cfg(windows)]
fn ico_dimension(value: u32) -> io::Result<u8> {
    match value {
        1..=255 => Ok(value as u8),
        256 => Ok(0),
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unsupported icon dimension: {value}"),
        )),
    }
}

#[cfg(windows)]
fn write_android_launcher_ico(manifest_dir: &Path, destination: &Path) -> io::Result<()> {
    let mut images = Vec::with_capacity(ANDROID_ICON_PATHS.len());
    for relative in ANDROID_ICON_PATHS {
        let source = manifest_dir.join(relative);
        println!("cargo:rerun-if-changed={}", source.display());
        let data = fs::read(source)?;
        let (width, height) = png_dimensions(&data)?;
        images.push((ico_dimension(width)?, ico_dimension(height)?, data));
    }

    let count = u16::try_from(images.len()).expect("launcher icon count fits u16");
    let directory_size = 6_u32 + u32::from(count) * 16;
    let mut offset = directory_size;
    let mut output = fs::File::create(destination)?;
    output.write_all(&0_u16.to_le_bytes())?;
    output.write_all(&1_u16.to_le_bytes())?;
    output.write_all(&count.to_le_bytes())?;

    for (width, height, data) in &images {
        output.write_all(&[*width, *height, 0, 0])?;
        output.write_all(&1_u16.to_le_bytes())?;
        output.write_all(&32_u16.to_le_bytes())?;
        output.write_all(
            &u32::try_from(data.len())
                .expect("launcher icon size fits u32")
                .to_le_bytes(),
        )?;
        output.write_all(&offset.to_le_bytes())?;
        offset = offset
            .checked_add(u32::try_from(data.len()).expect("launcher icon size fits u32"))
            .expect("ICO size fits u32");
    }
    for (_, _, data) in images {
        output.write_all(&data)?;
    }
    output.sync_all()?;
    Ok(())
}

#[cfg(windows)]
fn version_number(version: &str) -> u64 {
    let mut parts = [0_u16; 4];
    for (index, part) in version.split('.').take(4).enumerate() {
        parts[index] = part
            .split_once('-')
            .map_or(part, |(numeric, _)| numeric)
            .parse::<u16>()
            .unwrap_or(0);
    }
    (u64::from(parts[0]) << 48)
        | (u64::from(parts[1]) << 32)
        | (u64::from(parts[2]) << 16)
        | u64::from(parts[3])
}

#[cfg(windows)]
fn main() {
    use winres::{VersionInfo, WindowsResource};

    let manifest_dir =
        PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").expect("Cargo manifest directory is set"));
    let out_dir = PathBuf::from(env::var_os("OUT_DIR").expect("OUT_DIR is set by Cargo"));
    let icon = out_dir.join("AstralWindowsPatcher.ico");
    write_android_launcher_ico(&manifest_dir, &icon)
        .expect("failed to build Windows icon from Android launcher");

    let version = env::var("CARGO_PKG_VERSION").expect("Cargo package version is available");
    let numeric_version = version_number(&version);
    let icon = icon.to_string_lossy();
    let mut resource = WindowsResource::new();
    resource
        .set_icon(icon.as_ref())
        .set_language(0x0412)
        .set("CompanyName", "MayNut")
        .set("FileDescription", "Astral Party Korean Patch Manager")
        .set("FileVersion", &version)
        .set("InternalName", "AstralWindowsPatcher")
        .set("OriginalFilename", "AstralWindowsPatcher.exe")
        .set("ProductName", "Astral Party Windows Patcher")
        .set("ProductVersion", &version)
        .set("LegalCopyright", "Copyright (c) MayNut")
        .set_version_info(VersionInfo::FILEVERSION, numeric_version)
        .set_version_info(VersionInfo::PRODUCTVERSION, numeric_version);
    resource.compile().expect("failed to compile Windows resources");
}

#[cfg(not(windows))]
fn main() {}
