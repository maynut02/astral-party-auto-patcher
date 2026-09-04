[CmdletBinding()]
param(
    [switch]$SkipSdkPackages
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$GradleVersion = '9.4.1'
$ProjectRoot = $PSScriptRoot
$RepoRoot = Split-Path $ProjectRoot -Parent
$ToolsRoot = Join-Path $RepoRoot '.local-tools'
$RequiredSdkPackages = @(
    @{
        AndroidCli = 'platforms/android-37.0'
        SdkManager = 'platforms;android-37.0'
        Probe = 'platforms\android-37.0\android.jar'
    },
    @{
        AndroidCli = 'build-tools/36.0.0'
        SdkManager = 'build-tools;36.0.0'
        Probe = 'build-tools\36.0.0\aapt2.exe'
    },
    @{
        AndroidCli = 'platform-tools'
        SdkManager = 'platform-tools'
        Probe = 'platform-tools\adb.exe'
    }
)

function Get-MissingSdkPackages([string]$SdkRoot) {
    return @(
        $RequiredSdkPackages | Where-Object {
            -not (Test-Path (Join-Path $SdkRoot $_.Probe))
        }
    )
}

function Resolve-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    if ($env:ProgramFiles) {
        $candidates += (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')
        $candidates += (Join-Path $env:ProgramFiles 'Android\Android Studio\jre')
    }
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr')
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) {
            return (Resolve-Path $candidate).Path
        }
    }

    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) {
        return (Split-Path (Split-Path $java.Source -Parent) -Parent)
    }

    throw 'JDK 17을 찾지 못했습니다. Android Studio를 설치하거나 JAVA_HOME을 JDK 17로 설정하세요.'
}

function Resolve-AndroidSdk {
    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw 'Android SDK를 찾지 못했습니다. Android Studio의 SDK Manager에서 Android SDK를 설치하세요.'
}

function Resolve-AndroidCli([string]$SdkRoot) {
    $cmdlineRoot = Join-Path $SdkRoot 'cmdline-tools'
    if (-not (Test-Path $cmdlineRoot)) { return $null }

    $bins = @(
        (Join-Path $cmdlineRoot 'latest\bin\android.exe'),
        (Join-Path $cmdlineRoot 'latest\bin\android.bat'),
        (Join-Path $cmdlineRoot 'latest\bin\android.cmd')
    )
    $bins += Get-ChildItem $cmdlineRoot -Directory |
        Sort-Object Name -Descending |
        ForEach-Object {
            @(
                (Join-Path $_.FullName 'bin\android.exe'),
                (Join-Path $_.FullName 'bin\android.bat'),
                (Join-Path $_.FullName 'bin\android.cmd')
            )
        }

    return $bins | Where-Object { Test-Path $_ } | Select-Object -First 1
}

function Resolve-SdkManager([string]$SdkRoot) {
    $latest = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (Test-Path $latest) { return $latest }

    $cmdlineRoot = Join-Path $SdkRoot 'cmdline-tools'
    if (Test-Path $cmdlineRoot) {
        $candidate = Get-ChildItem $cmdlineRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin\sdkmanager.bat' } |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($candidate) { return $candidate }
    }

    return $null
}

function Ensure-GradleWrapper {
    $wrapper = Join-Path $ProjectRoot 'gradlew.bat'
    if (Test-Path $wrapper) { return }

    New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null
    $zip = Join-Path $ToolsRoot "gradle-$GradleVersion-bin.zip"
    $gradleHome = Join-Path $ToolsRoot "gradle-$GradleVersion"
    if (-not (Test-Path (Join-Path $gradleHome 'bin\gradle.bat'))) {
        if (-not (Test-Path $zip)) {
            Write-Host "Gradle $GradleVersion 다운로드 중..."
            Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $zip
        }
        Write-Host "Gradle $GradleVersion 압축 해제 중..."
        Expand-Archive -Path $zip -DestinationPath $ToolsRoot -Force
    }

    Write-Host 'Gradle Wrapper 생성 중...'
    & (Join-Path $gradleHome 'bin\gradle.bat') -p $ProjectRoot wrapper --gradle-version $GradleVersion --distribution-type bin
    if ($LASTEXITCODE -ne 0) { throw 'Gradle Wrapper 생성에 실패했습니다.' }
}

$javaHome = Resolve-JavaHome
$env:JAVA_HOME = $javaHome
$javaExe = Join-Path $javaHome 'bin\java.exe'
$javaVersionOutput = (& $javaExe -version 2>&1 | Out-String)
if ($javaVersionOutput -notmatch 'version "(\d+)') {
    throw 'JDK 버전을 확인하지 못했습니다.'
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 17) {
    throw "JDK 17 이상이 필요합니다. 현재 JDK: $javaMajor"
}

$sdkRoot = Resolve-AndroidSdk
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_HOME = $sdkRoot

if (-not $SkipSdkPackages) {
    Write-Host '필수 Android SDK 패키지 설치/확인 중...'
    $missingPackages = @(Get-MissingSdkPackages $sdkRoot)
    if ($missingPackages.Count -eq 0) {
        Write-Host '필수 Android SDK 패키지가 이미 설치되어 있습니다.'
    } else {
        $androidCli = Resolve-AndroidCli $sdkRoot
        $sdkManager = Resolve-SdkManager $sdkRoot
        $installerExitCode = 0

        if ($androidCli) {
            Write-Host "Android CLI 사용: $androidCli"
            $packageNames = @($missingPackages | ForEach-Object { $_.AndroidCli })
            & $androidCli sdk install @packageNames
            $installerExitCode = $LASTEXITCODE
        } elseif ($sdkManager) {
            Write-Host "sdkmanager fallback 사용: $sdkManager"
            $packageFile = Join-Path $ToolsRoot 'android-sdk-packages.txt'
            New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null
            $packageNames = @($missingPackages | ForEach-Object { $_.SdkManager })
            [System.IO.File]::WriteAllLines(
                $packageFile,
                $packageNames,
                [System.Text.UTF8Encoding]::new($false)
            )
            & $sdkManager --sdk_root=$sdkRoot --package_file=$packageFile
            $installerExitCode = $LASTEXITCODE
        } else {
            throw 'Android CLI 또는 sdkmanager를 찾지 못했습니다. Android Studio > SDK Manager > SDK Tools에서 Android SDK Command-line Tools (latest)를 설치하세요.'
        }

        $missingPackages = @(Get-MissingSdkPackages $sdkRoot)
        if ($missingPackages.Count -gt 0) {
            $missingNames = ($missingPackages | ForEach-Object { $_.SdkManager }) -join ', '
            throw "Android SDK 패키지 설치에 실패했습니다 (종료 코드: $installerExitCode). 미설치 패키지: $missingNames"
        }
        if ($installerExitCode -ne 0) {
            Write-Warning "Android CLI가 비정상 종료 코드 $installerExitCode를 반환했지만 필요한 SDK 패키지는 모두 설치된 것으로 확인했습니다."
        }
    }
}

$sdkForProperties = $sdkRoot.Replace('\', '/').Replace(':', '\:')
$localProperties = Join-Path $ProjectRoot 'local.properties'
[System.IO.File]::WriteAllText(
    $localProperties,
    "sdk.dir=$sdkForProperties`n",
    [System.Text.UTF8Encoding]::new($false)
)

Ensure-GradleWrapper

Write-Host ''
Write-Host "JAVA_HOME=$javaHome"
Write-Host "ANDROID_SDK_ROOT=$sdkRoot"
Write-Host "Gradle=$GradleVersion"
Write-Host '로컬 Android 빌드 환경 준비가 완료되었습니다.'
Write-Host '다음 명령으로 빌드할 수 있습니다:'
Write-Host '  .\build-local.ps1'
