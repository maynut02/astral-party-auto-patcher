# AndroidPatcher

Astral Party Android판의 외부 Addressables 캐시에 한국어 패치를 적용하는 Android 11 이상용 클라이언트입니다. UI는 Jetpack Compose Material 3 Expressive를 사용합니다.

지원 대상은 다음 세 설치본입니다.

- 일본 버전: `com.feimo.astralpartyjpn` → `INT_ANDROID`
- 중국 버전: `com.feimo.astralparty` → `CN_ANDROID`
- 빌리빌리 버전: `com.feimo.astralparty.bilibili` → `CN_ANDROID`

중국 버전과 빌리빌리 버전은 동일한 `CN_ANDROID` manifest/payload를 사용하고 Android package 경로만 다릅니다.

## 동작 방식

1. Shizuku 없이 `PackageManager`로 세 게임 package의 설치 여부와 버전을 확인하고 patch 대상을 선택합니다. 일본 버전은 미설치 상태에서도 선택할 수 있지만 중국 버전과 빌리빌리 버전은 설치되어 있어야 선택할 수 있습니다.
2. 일본 버전을 선택한 경우에만 별도 immutable Release의 Google Play 원본 split APK 설치 기능을 제공합니다.
3. Shizuku 연결과 AndroidPatcher 권한을 확인합니다.
4. 선택 package가 설치되어 있으면 Shizuku UserService가 해당 package의 `com.unity.addressables` catalog version/hash와 AndroidPatcher 상태를 읽습니다.
5. 고정된 `release-index.json`에서 선택 대상의 route와 정확히 일치하는 정식 패치를 찾습니다.
6. 검증된 manifest에 기록된 patch 대상 원본 파일의 존재 여부를 먼저 확인해 게임 리소스 다운로드 완료 상태를 판별합니다.
7. 동일한 게임 버전과 revision에 고정된 원본 Release에서 복원용 gzip을 내려받고 압축 파일과 원본 payload를 모두 검증합니다.
8. 검증된 Release 원본을 영구 restore copy로 저장하고, 현재 게임 파일은 transaction rollback용 임시 copy만 생성합니다.
9. patch gzip/payload의 크기와 SHA-256을 검증한 뒤 Addressables bundle을 교체합니다.
10. 중간 실패 시 transaction rollback copy로 자동 복구하고, 사용자는 검증된 Release 원본을 기준으로 원본 파일을 복원할 수 있습니다.

일본 버전 원본 설치 기능은 Actions가 Google Play에서 받은 base/split APK를 병합·수정·재서명하지 않고 그대로 배포합니다. AndroidPatcher는 index의 크기와 SHA-256, packageName, versionCode 및 Google Play 인증서를 검증한 뒤 Shizuku shell 권한으로 모든 split을 한 번에 설치합니다. 설치 session의 기록상 installer는 `com.android.vending`으로 지정합니다. 중국 버전과 빌리빌리 버전은 원본 APK 설치 기능을 제공하지 않습니다.

## 요구 사항

- Android 11 이상
- 패치할 게임 설치
  - 일본 버전은 AndroidPatcher에서 원본 split 설치 가능
  - 중국 버전/빌리빌리 버전은 사용자가 미리 설치해야 함
- 게임 최초 실행과 최신 리소스 다운로드 완료
- Shizuku 설치·서비스 시작·AndroidPatcher 권한 허용
- 인터넷 연결

게임 package 탐지와 버전 선택은 Shizuku 없이 동작합니다. 다른 앱의 app-specific external directory를 읽고 수정하는 patch 상태/리소스 검사는 Shizuku shell 권한이 필요합니다. 제조사와 Android 버전에 따라 shell의 `/storage/emulated/0/Android/data/...` 접근 동작이 다를 수 있어 실제 기기 검증이 필요합니다.

Shizuku가 설치되지 않았으면 앱이 GitHub 최신 안정 APK를 내려받아 SHA-256과 packageName을 검증한 뒤 Android 시스템 설치 화면을 엽니다. Android 보안 정책상 사용자의 설치 확인은 생략하지 않습니다.

앱은 `mobile-patcher-index.json`에서 새 AndroidPatcher 릴리스를 확인합니다. 새 버전이 있으면 APK의 크기, SHA-256, packageName, versionName, versionCode와 현재 앱의 서명을 모두 검증한 뒤 시스템 업데이트 설치 화면을 엽니다.

## 보안 경계

- privileged service가 허용하는 게임 package는 다음 세 값으로 제한합니다.
  - `com.feimo.astralpartyjpn`
  - `com.feimo.astralparty`
  - `com.feimo.astralparty.bilibili`
- 클라이언트가 전달한 임의 package 문자열로 `/Android/data/...` 경로를 만들지 않습니다.
- patch transaction은 시작 시 선택 package를 기록하고 이후 모든 stage/apply/commit/rollback 호출에서 동일 package인지 재검증합니다.
- Shizuku 서비스는 임의 shell command API를 노출하지 않습니다.
- manifest의 `game-data` target과 안전하지 않은 상대 경로를 거부합니다.
- index, manifest, payload URL은 프로젝트의 HTTPS GitHub 경로로 제한합니다.
- Shizuku APK는 고정된 GitHub repository의 안정 release만 허용하고 release digest와 packageName을 검증합니다.
- AndroidPatcher 자체 업데이트는 고정된 distribution index와 release 경로만 허용하고 현재 앱과 동일한 서명인지 확인합니다.
- payload는 앱 프로세스와 shell 서비스 양쪽에서 SHA-256/크기를 검증합니다.
- patch 시작 전에 선택한 게임 package를 강제 종료합니다.
- crash가 남긴 transaction은 다음 진단 시 해당 package의 원래 파일로 복구합니다.
- 일본 버전 원본 게임 설치 서비스는 고정된 `pm install-create/install-write/install-commit` session 동작만 제공하고 installer를 `com.android.vending`으로 지정하며, 임의 shell command를 받지 않습니다.

## 원본 게임 APK Release

`INT_ANDROID APK` workflow는 `PLAY_EMAIL`, `AAS_TOKEN` secrets로 `com.feimo.astralpartyjpn`의 Pixel 9a 기기 프로필용 Google Play split APK를 받습니다. 각 APK를 `apksigner`와 `aapt`로 검증한 뒤 `android-game-v<versionCode>` immutable Release로 발행하고 distribution branch의 `android-game-index.json`을 갱신합니다.

현재 원본 묶음은 `px_9a` 프로필 하나를 대상으로 하므로 다른 ABI 또는 일부 기기 구성에서는 Android package manager가 설치를 거부할 수 있습니다. 또한 기록상 installer 지정은 Google Play 계정 라이선스를 부여하지 않습니다.

## 로컬 빌드

현재 로컬/CI 빌드 기준은 JDK 17 이상, Gradle 9.4.1, Android SDK 37.0/Build Tools 36.0.0입니다. Material 3 Expressive는 `androidx.compose.material3:material3:1.5.0-alpha27`을 명시적으로 사용합니다.

Windows PowerShell에서 최초 한 번 실행합니다.

```powershell
cd C:\Users\Home\Documents\Works\astral-party-auto-patcher\android-patcher
.\setup-local.ps1
```

`setup-local.ps1`은 다음을 수행합니다.

- `JAVA_HOME` 또는 Android Studio의 JBR에서 JDK를 탐지
- `ANDROID_SDK_ROOT`, `ANDROID_HOME`, `%LOCALAPPDATA%\Android\Sdk`에서 SDK 탐지
- SDK command-line tools가 있으면 `platforms;android-37.0`, `build-tools;36.0.0`, `platform-tools` 설치/확인
- 프로젝트 전용 `local.properties` 생성
- Gradle 9.4.1을 `.local-tools`에 내려받아 Gradle Wrapper 생성

그 다음부터 debug APK는 다음 한 줄로 빌드합니다.

```powershell
.\build-local.ps1
```

기본 동작은 `testDebugUnitTest`, `lintDebug`, `assembleDebug`입니다. 결과 APK:

```text
android-patcher\app\build\outputs\apk\debug\app-debug.apk
```

단위 테스트와 lint를 생략하고 APK만 빠르게 빌드하려면:

```powershell
.\build-local.ps1 -SkipChecks
```

클린 빌드:

```powershell
.\build-local.ps1 -Clean
```

release 빌드는 기존 `ANDROID_KEYSTORE_*` 환경변수가 준비되어 있을 때 사용할 수 있습니다.

```powershell
.\build-local.ps1 -Configuration Release
```

GitHub Actions의 `AndroidPatcher` workflow는 `ANDROID_KEYSTORE_*` secrets로 release APK를 서명하고 `android-patcher-v<version>` immutable Release를 생성합니다. 배포 파일은 `AstralAndroidPatcher.apk`입니다.
