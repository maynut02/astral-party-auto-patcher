# AndroidPatcher

Astral Party Android판의 외부 Addressables 캐시에 한국어 패치를 적용하는 Android 11 이상용 클라이언트입니다. UI는 Jetpack Compose Material 3 Expressive를 사용합니다.

지원 대상은 다음 세 설치본입니다.

- 일본 버전: `com.feimo.astralpartyjpn` → `INT_ANDROID`
- 중국 버전: `com.feimo.astralparty` → `CN_ANDROID`
- 빌리빌리 버전: `com.feimo.astralparty.bilibili` → `CN_ANDROID`

중국 버전과 빌리빌리 버전은 동일한 `CN_ANDROID` manifest/payload를 사용하고 Android package 경로만 다릅니다.

## 동작 방식

1. `PackageManager`로 게임 설치본의 버전 이름·코드, 업데이트 시각, APK 경로와 사용자 프로필을 확인합니다. 설치 출처 조회 실패는 미설치로 취급하지 않습니다.
2. 일본 버전은 별도 immutable Release의 Google Play 원본 split APK 설치 기능을 제공합니다.
3. Shizuku 연결과 권한을 확인하고 선택 package의 카탈로그 후보와 설치 이력을 각각 읽습니다. 진단은 파일을 변경하지 않습니다.
4. APK의 `assets/aa/settings.json`에 기록된 카탈로그 이름을 우선하며, 해당 package의 외부 `files` 범위 안에서 대상 번들 이름·해시 경로를 제한적으로 탐색합니다. 동적 캐시 설정은 평가하지 않고 진단 로그에 남깁니다. 실제 대상 파일을 모두 확인한 뒤에는 이 설정만으로 호환성 경고를 띄우지 않습니다.
5. 선택한 게임 route의 최신 정식 한글패치를 자동으로 사용합니다. APK 버전이나 로컬 카탈로그와 맞는 과거 배포로 대체하지 않으며 버전 선택 목록은 제공하지 않습니다. 설치된 리소스와 최신 한글패치 리소스를 표시하고 같은 패치 재설치도 지원합니다.
6. 설치·원본 복구 버튼을 누를 때 배포 인덱스를 다시 읽어 최신 manifest를 고정합니다. 관측한 게임 리소스 버전 또는 카탈로그 식별값이 최신 대상과 다를 때만 확인 모달을 표시합니다. 구패치의 내용 차이·동적 캐시 설정·미확인 진단은 추가 모달 조건이 아니며 상태와 로그에 남깁니다. 파일 누락·접근 실패·복수 경로는 구체적인 이유와 함께 쓰기를 중단합니다.
7. 최신 index 항목의 digest로 manifest를 검증합니다. 설치는 패치만, 원본 복구는 manifest의 `sourceDownloadUrl` 원본만 받으며, 모든 gzip과 압축 해제 payload의 크기 및 SHA-256을 파일 작업 전에 검증합니다. 설치 시 복구용 원본을 별도로 다운로드하거나 영구 보관하지 않습니다.
8. 설치본과 관측 파일을 다시 확인한 뒤 게임을 종료합니다. 서비스는 계획에 고정된 실제 경로를 재검증하고 실패 복구를 위한 모든 대상의 임시 변경 전 사본을 준비합니다. 정상 완료 또는 롤백 완료 후 이 사본은 삭제합니다.
9. 설치와 원본 복구는 같은 저널 기반 트랜잭션을 사용합니다. 모든 대상 파일의 적용 결과가 일치해야 완료 기록을 확정합니다. 중간 실패 시 변경 전 파일과 설치 이력을 복원합니다.
10. 원본 복구는 최신 한글패치 manifest의 `sourceDownloadUrl`이 가리키는 GitHub 원본 릴리스를 사용합니다. 로컬 설치 이력이나 영구 원본 백업이 없어도 시도할 수 있으며, 최신 배포의 패치 대상 리소스를 순정으로 돌립니다. 설치된 리소스와 대상이 다르면 설치와 동일하게 확인 모달을 표시합니다.

한글패치 상태는 **미설치·원본 복구 후 회색**, **최신 패치 설치 완료 초록색**, **검증된 구버전 패치 설치 노란색**, **상태 확인·작업 오류 빨간색**으로 표시합니다. 구버전은 설치 이력만으로 추정하지 않고 같은 리소스 카탈로그의 이전 manifest와 실제 파일 크기·SHA-256을 비교합니다. 과거 manifest를 확인해도 설치·복구 실행 대상은 항상 최신 배포입니다. 최신 여부를 확인하지 못한 파일을 구버전으로 단정하지 않습니다.

일본 버전 원본 설치 기능은 Actions가 Google Play에서 받은 base/split APK를 병합·수정·재서명하지 않고 그대로 배포합니다. AndroidPatcher는 index의 크기와 SHA-256, packageName, versionCode 및 Google Play 인증서를 검증한 뒤 Shizuku shell 권한으로 모든 split을 한 번에 설치합니다. 설치 session의 기록상 installer는 `com.android.vending`으로 지정합니다. 중국 버전과 빌리빌리 버전은 원본 APK 설치 기능을 제공하지 않습니다.

## 요구 사항

- Android 11 이상
- 패치할 게임 설치
  - 일본 버전은 AndroidPatcher에서 원본 split 설치 가능
  - 중국 버전/빌리빌리 버전은 사용자가 미리 설치해야 함
- 게임 최초 실행과 최신 리소스 다운로드 완료
- Shizuku 설치·서비스 시작·AndroidPatcher 권한 허용
- 인터넷 연결

게임 package 탐지와 최신 배포 조회는 Shizuku 없이 동작합니다. 다른 앱의 app-specific external directory를 읽고 수정하는 patch 상태/리소스 검사는 Shizuku shell 권한이 필요합니다. 제조사와 Android 버전에 따라 shell의 `/storage/emulated/0/Android/data/...` 접근 동작이 다를 수 있어 실제 기기 검증이 필요합니다.

Shizuku가 설치되지 않았으면 앱이 GitHub 최신 안정 APK를 내려받아 SHA-256과 packageName을 검증한 뒤 Android 시스템 설치 화면을 엽니다. Android 보안 정책상 사용자의 설치 확인은 생략하지 않습니다.

앱은 `mobile-patcher-index.json`에서 새 AndroidPatcher 릴리스를 확인합니다. 새 버전이 있으면 APK의 크기, SHA-256, packageName, versionName, versionCode와 현재 앱의 서명을 모두 검증한 뒤 시스템 업데이트 설치 화면을 엽니다.

## 보안 경계

- privileged service가 허용하는 게임 package는 다음 세 값으로 제한합니다.
  - `com.feimo.astralpartyjpn`
  - `com.feimo.astralparty`
  - `com.feimo.astralparty.bilibili`
- 클라이언트가 전달한 임의 package 문자열로 `/Android/data/...` 경로를 만들지 않습니다.
- patch transaction은 시작 시 선택 package를 기록하고 이후 모든 apply/commit/rollback 호출에서 동일 package인지 재검증합니다.
- Shizuku 서비스는 임의 shell command API를 노출하지 않습니다.
- manifest의 `game-data` target과 안전하지 않은 상대 경로를 거부합니다.
- index, manifest, payload URL은 프로젝트의 HTTPS GitHub 경로로 제한합니다.
- Shizuku APK는 고정된 GitHub repository의 안정 release만 허용하고 release digest와 packageName을 검증합니다.
- AndroidPatcher 자체 업데이트는 고정된 distribution index와 release 경로만 허용하고 현재 앱과 동일한 서명인지 확인합니다.
- payload는 앱 프로세스와 shell 서비스 양쪽에서 SHA-256/크기를 검증합니다.
- patch 시작 전에 선택한 게임 package를 강제 종료합니다.
- 중단된 transaction은 다음 파일 작업 시작 시 먼저 복구합니다. 읽기 전용 진단은 복구나 파일 쓰기를 실행하지 않습니다.
- 선택한 릴리스의 카탈로그 해시와 관측한 로컬 카탈로그 해시를 분리해 보관합니다.
- 실제 경로가 여러 개이거나 탐색이 불완전하면 첫 번째 파일을 임의로 선택하지 않습니다.
- 현재 파일 작업은 기본 사용자 프로필(user 0)을 지원하며, 보조 프로필에서는 명시적으로 중단합니다.
- 일본 버전 원본 게임 설치 서비스는 고정된 `pm install-create/install-write/install-commit` session 동작만 제공하고 installer를 `com.android.vending`으로 지정하며, 임의 shell command를 받지 않습니다.

## 진단 범위와 실기기 확인

카탈로그 `.hash`는 배포 식별자이며 JSON의 MD5나 `m_BuildResultHash`와 같다고 가정하지 않습니다. 로컬 JSON은 별도 크기·테이블 범위 제한을 적용해 번들 레코드를 검사합니다. 바이너리 `catalog.bundle` 해석과 게임 실행 중 실제로 사용되는 캐시 확인은 지원하지 않으며, 로컬 관측만으로 활성 카탈로그를 보장하지 않습니다.

GET 다운로드의 일시 HTTP 오류(408·429·500·502·503·504)와 네트워크 전송 중단은 최대 3회 시도합니다. 매번 원래 공개 URL부터 리다이렉트하며, Retry-After를 존중합니다. 무결성·경로·TLS 인증 오류는 재시도하지 않습니다. 로그에는 파일 URL, 응답 호스트·상태, 재시도 횟수를 남기며 CDN 서명 쿼리는 기록하지 않습니다.

파일 작업 중 요청된 새로고침은 종료 후 실행됩니다. 검사 도중 같은 package가 업데이트되거나 새로고침되면 이전 결과를 버리고 최신 요청으로 재검사합니다. PackageManager·Shizuku·제조사별 외부 저장소 접근 및 강제 종료 중단 복구는 실제 Android 기기에서 별도로 검증해야 합니다.

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
