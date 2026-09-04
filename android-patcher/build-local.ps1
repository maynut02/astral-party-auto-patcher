[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug',
    [switch]$SkipChecks,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ProjectRoot = $PSScriptRoot
$wrapper = Join-Path $ProjectRoot 'gradlew.bat'
if (-not (Test-Path $wrapper)) {
    & (Join-Path $ProjectRoot 'setup-local.ps1')
}

$tasks = @()
if ($Clean) { $tasks += 'clean' }
if (-not $SkipChecks) {
    $tasks += 'testDebugUnitTest'
    if ($Configuration -eq 'Release') {
        $tasks += 'lintRelease'
    } else {
        $tasks += 'lintDebug'
    }
}
if ($Configuration -eq 'Release') {
    $tasks += 'assembleRelease'
} else {
    $tasks += 'assembleDebug'
}

Push-Location $ProjectRoot
try {
    & $wrapper @tasks
    if ($LASTEXITCODE -ne 0) { throw 'Gradle Android 빌드에 실패했습니다.' }
} finally {
    Pop-Location
}

if ($Configuration -eq 'Release') {
    $apk = Join-Path $ProjectRoot 'app\build\outputs\apk\release\app-release.apk'
} else {
    $apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
}

if (Test-Path $apk) {
    Write-Host ''
    Write-Host "APK: $apk"
} else {
    Write-Warning "Gradle 작업은 성공했지만 예상 APK 경로를 찾지 못했습니다: $apk"
}
