param(
    [string]$Version = '1.0.0',
    [string]$WorkRoot = '',
    [string]$OutputRoot = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version must use semantic X.Y.Z format: $Version"
}

$pluginRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $WorkRoot) { $WorkRoot = Join-Path $pluginRoot '.work' }
if (-not $OutputRoot) { $OutputRoot = Join-Path $pluginRoot 'dist' }
$work = [IO.Path]::GetFullPath($WorkRoot)
$dist = [IO.Path]::GetFullPath($OutputRoot)
$deps = Join-Path $work 'deps'
$bepinexRoot = Join-Path $deps 'bepinex'
$unityRoot = Join-Path $deps 'unity'
$downloads = Join-Path $work 'downloads'
$stage = Join-Path $work 'stage'

$BepInExUrl = 'https://builds.bepinex.dev/projects/bepinex_be/788/BepInEx-Unity.IL2CPP-win-x64-6.0.0-be.788%2B5b766a3.zip'
$BepInExSha256 = 'f4cc496bd098a0df4164b81e3737297707f13a47c2478dba2f60eefab784817a'
$UnityLibrariesUrl = 'https://unity.bepinex.dev/libraries/2022.3.62.zip'
$UnityLibrariesSha256 = '575e7d600f69de8200ccf4db700b3ae6252366c22e8c3434c860e428974518d1'
$BepInExLicenseUrl = 'https://raw.githubusercontent.com/BepInEx/BepInEx/5b766a3/LICENSE'
$BepInExLicenseSha256 = 'f2ceca48af033d8adf337a8f9453e633cfad10a1f8c9fec2e5ae55a2d8ffe952'

function Reset-Directory([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
}

function Assert-Sha256([string]$Path, [string]$Expected) {
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Path. expected=$Expected actual=$actual"
    }
}

function Get-VerifiedFile([string]$Url, [string]$Destination, [string]$Sha256) {
    if (Test-Path -LiteralPath $Destination) {
        try {
            Assert-Sha256 $Destination $Sha256
            return
        }
        catch {
            Remove-Item -LiteralPath $Destination -Force
        }
    }

    $headers = @{ 'User-Agent' = 'AstralPartyKoreanPatch-Packager/1.0' }
    $lastError = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $temp = "$Destination.part"
        try {
            if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
            Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $Url -OutFile $temp
            Assert-Sha256 $temp $Sha256
            Move-Item -LiteralPath $temp -Destination $Destination -Force
            return
        }
        catch {
            $lastError = $_
            if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
            if ($attempt -lt 3) { Start-Sleep -Seconds (2 * $attempt) }
        }
    }
    throw "Failed to download verified dependency: $Url`n$lastError"
}

function Expand-ZipClean([string]$ZipPath, [string]$Destination) {
    Reset-Directory $Destination
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $Destination -Force
}

function Invoke-DotNetBuild([string]$Project) {
    & dotnet build $Project --configuration Release --nologo `
        "-p:AstralDepsRoot=$deps" `
        -p:ContinuousIntegrationBuild=true
    if ($LASTEXITCODE -ne 0) { throw "dotnet build failed: $Project" }
}

function Read-PreloaderVersion([string]$SourcePath) {
    $text = Get-Content -LiteralPath $SourcePath -Raw
    $match = [regex]::Match($text, '(?s)\[PatcherPluginInfo\(\s*"[^"]+"\s*,\s*"[^"]+"\s*,\s*"([^"]+)"\s*\)\]')
    if (!$match.Success) { throw "Could not read preloader version from $SourcePath" }
    return $match.Groups[1].Value
}

function Read-PluginVersion([string]$SourcePath) {
    $text = Get-Content -LiteralPath $SourcePath -Raw
    $match = [regex]::Match($text, 'public\s+const\s+string\s+PluginVersion\s*=\s*"([^"]+)"')
    if (!$match.Success) { throw "Could not read plugin version from $SourcePath" }
    return $match.Groups[1].Value
}

New-Item -ItemType Directory -Path $work,$dist,$deps,$downloads -Force | Out-Null

$bepinexZip = Join-Path $downloads 'BepInEx-Unity.IL2CPP-win-x64-6.0.0-be.788+5b766a3.zip'
$unityZip = Join-Path $downloads 'Unity-2022.3.62-libraries.zip'
$bepinexLicense = Join-Path $downloads 'BepInEx-LICENSE.txt'
Get-VerifiedFile $BepInExUrl $bepinexZip $BepInExSha256
Get-VerifiedFile $UnityLibrariesUrl $unityZip $UnityLibrariesSha256
Get-VerifiedFile $BepInExLicenseUrl $bepinexLicense $BepInExLicenseSha256
Expand-ZipClean $bepinexZip $bepinexRoot
Expand-ZipClean $unityZip $unityRoot

$requiredDependencies = @(
    (Join-Path $bepinexRoot 'BepInEx/core/BepInEx.Core.dll'),
    (Join-Path $bepinexRoot 'BepInEx/core/BepInEx.Preloader.Core.dll'),
    (Join-Path $bepinexRoot 'BepInEx/core/BepInEx.Unity.IL2CPP.dll'),
    (Join-Path $bepinexRoot 'BepInEx/core/0Harmony.dll'),
    (Join-Path $bepinexRoot 'BepInEx/core/Il2CppInterop.Runtime.dll'),
    (Join-Path $bepinexRoot 'BepInEx/core/dobby.dll'),
    (Join-Path $unityRoot 'UnityEngine.CoreModule.dll'),
    (Join-Path $unityRoot 'UnityEngine.TextRenderingModule.dll'),
    (Join-Path $unityRoot 'UnityEngine.AssetBundleModule.dll'),
    (Join-Path $unityRoot 'UnityEngine.UIModule.dll'),
    (Join-Path $unityRoot 'UnityEngine.InputLegacyModule.dll')
)
foreach ($path in $requiredDependencies) {
    if (!(Test-Path -LiteralPath $path)) { throw "Required build dependency is missing: $path" }
}

$preloaderSource = Join-Path $pluginRoot 'src/Preloader/DataUnity3dRedirect.cs'
$pluginSource = Join-Path $pluginRoot 'src/Plugin/AddressablesInProcessPatch.cs'
$preloaderProject = Join-Path $pluginRoot 'src/Preloader/AstralParty.DataUnity3dRedirect.csproj'
$pluginProject = Join-Path $pluginRoot 'src/Plugin/AstralParty.AddressablesInProcessPatch.csproj'
$preloaderVersion = Read-PreloaderVersion $preloaderSource
$pluginVersion = Read-PluginVersion $pluginSource
Invoke-DotNetBuild $preloaderProject
Invoke-DotNetBuild $pluginProject

$preloaderDll = Join-Path $pluginRoot 'src/Preloader/bin/Release/net6.0/AstralParty.DataUnity3dRedirect.dll'
$pluginDll = Join-Path $pluginRoot 'src/Plugin/bin/Release/net6.0/AstralParty.AddressablesInProcessPatch.dll'
foreach ($path in @($preloaderDll, $pluginDll)) {
    if (!(Test-Path -LiteralPath $path)) { throw "Build output is missing: $path" }
}

Reset-Directory $stage
Get-ChildItem -LiteralPath $bepinexRoot -Force | Copy-Item -Destination $stage -Recurse -Force

# Upstream documentation is not needed at runtime. Keep the BepInEx license,
# but omit its changelog so the release root only contains installable files.
$upstreamChangelog = Join-Path $stage 'changelog.txt'
if (Test-Path -LiteralPath $upstreamChangelog) {
    Remove-Item -LiteralPath $upstreamChangelog -Force
}

$configDir = Join-Path $stage 'BepInEx/config'
$patcherDir = Join-Path $stage 'BepInEx/patchers'
$pluginDir = Join-Path $stage 'BepInEx/plugins/AstralPartyKoreanPatch'
New-Item -ItemType Directory -Path $configDir,$patcherDir,$pluginDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $pluginRoot 'config/BepInEx.cfg') -Destination (Join-Path $configDir 'BepInEx.cfg') -Force
Copy-Item -LiteralPath $preloaderDll -Destination (Join-Path $patcherDir 'AstralParty.DataUnity3dRedirect.dll') -Force
Copy-Item -LiteralPath $pluginDll -Destination (Join-Path $pluginDir 'AstralParty.AddressablesInProcessPatch.dll') -Force
Copy-Item -LiteralPath (Join-Path $pluginRoot 'packaging/적용방법.txt') -Destination (Join-Path $stage '적용방법.txt') -Force
Copy-Item -LiteralPath $bepinexLicense -Destination (Join-Path $stage 'LICENSE-BepInEx.txt') -Force

$forbidden = @(
    'BepInEx/interop',
    'BepInEx/AstralPartyKoreanPatch',
    'BepInEx/LogOutput.log',
    'BepInEx/data-redirect.log',
    'BepInEx/ErrorLog.log',
    'changelog.txt',
    'README-KO.txt',
    'THIRD-PARTY-NOTICES.txt'
)
foreach ($relative in $forbidden) {
    if (Test-Path -LiteralPath (Join-Path $stage $relative)) {
        throw "Generated runtime file leaked into package: $relative"
    }
}

$requiredPackageFiles = @(
    'winhttp.dll',
    'doorstop_config.ini',
    '.doorstop_version',
    'BepInEx/core/BepInEx.Unity.IL2CPP.dll',
    'BepInEx/core/dobby.dll',
    'BepInEx/config/BepInEx.cfg',
    'BepInEx/patchers/AstralParty.DataUnity3dRedirect.dll',
    'BepInEx/plugins/AstralPartyKoreanPatch/AstralParty.AddressablesInProcessPatch.dll',
    '적용방법.txt',
    'LICENSE-BepInEx.txt'
)
foreach ($relative in $requiredPackageFiles) {
    if (!(Test-Path -LiteralPath (Join-Path $stage $relative))) {
        throw "Required package file is missing: $relative"
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = Join-Path $dist "AstralWindowsPlugin-v$Version.zip"
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $stage,
    $zipPath,
    [IO.Compression.CompressionLevel]::Optimal,
    $false
)

$zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
$preloaderHash = (Get-FileHash -LiteralPath $preloaderDll -Algorithm SHA256).Hash.ToLowerInvariant()
$pluginHash = (Get-FileHash -LiteralPath $pluginDll -Algorithm SHA256).Hash.ToLowerInvariant()
$metadata = [ordered]@{
    schemaVersion = 1
    packageVersion = $Version
    bepinexVersion = '6.0.0-be.788+5b766a3'
    unityReferenceVersion = '2022.3.62'
    package = [ordered]@{
        file = [IO.Path]::GetFileName($zipPath)
        sha256 = $zipHash
        size = (Get-Item $zipPath).Length
    }
    preloader = [ordered]@{ version = $preloaderVersion; sha256 = $preloaderHash }
    plugin = [ordered]@{ version = $pluginVersion; sha256 = $pluginHash }
}
$metadataPath = Join-Path $dist 'windows-plugin-build.json'
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding utf8NoBOM

Write-Output "package=$zipPath"
Write-Output "metadata=$metadataPath"
Write-Output "sha256=$zipHash"
Write-Output "preloader_version=$preloaderVersion"
Write-Output "plugin_version=$pluginVersion"
Write-Output "preloader_sha256=$preloaderHash"
Write-Output "plugin_sha256=$pluginHash"
