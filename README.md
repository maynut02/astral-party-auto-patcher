# Astral Party Auto Patcher

Astral Party 한국어 패치를 설치하는 Windows/Android 클라이언트와 Android 원본 게임 APK 배포 자동화를 관리합니다.

## 구성

- `windows-patcher/` — Windows Steam 글로벌/중국판 패치 설치·제거 클라이언트
- `android-patcher/` — Android INT 패치 설치·복원 및 원본 게임 설치 클라이언트
- `.github/workflows/windows-patcher.yml` — WindowsPatcher 릴리즈
- `.github/workflows/android-patcher.yml` — AndroidPatcher 릴리즈
- `.github/workflows/android-game-original.yml` — Google Play 원본 split APK 릴리즈
- `distribution` branch
  - `patcher-index.json` — WindowsPatcher 업데이트
  - `mobile-patcher-index.json` — AndroidPatcher 업데이트
  - `android-game-index.json` — 원본 Android 게임 APK

패치 manifest와 패치 파일은 별도 저장소 `maynut02/astral-party-korean-patch`에서 관리합니다. 두 Patcher는 해당 저장소의 `distribution/release-index.json`을 읽어 현재 게임에 맞는 한국어 패치를 찾습니다.

## 개발

### WindowsPatcher

```bash
cd windows-patcher
cargo fmt --all -- --check
cargo test --locked --all-targets --all-features
cargo clippy --locked --all-targets --all-features -- -D warnings
```

### AndroidPatcher

JDK 17, Gradle 9.4.1, Android SDK 36이 필요합니다.

```bash
cd android-patcher
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

필요한 GitHub Actions secrets는 [`.env.example`](.env.example)에 정리되어 있습니다.

## 라이선스

[MIT License](LICENSE)

## 저장소 이전

기존 `astral-party-korean-patch` 저장소에서 배포된 Patcher는 자체 업데이트 URL과 Release asset 경로를 기존 저장소로 고정 검증하므로 기존 설치본을 새 저장소로 넘기기 위한 일회성 브리지 릴리즈가 필요합니다.

새 저장소의 `Migration Bridge` workflow가 이 이전을 한 번에 처리합니다. 실행 전 `LEGACY_REPO_TOKEN` Secret에 기존 `maynut02/astral-party-korean-patch` 저장소의 Contents 쓰기 권한을 가진 fine-grained token을 등록하고, Android 릴리즈 서명용 기존 keystore Secrets도 동일하게 등록해야 합니다.

workflow는 기존 저장소의 최신 Patcher 버전을 읽어 각각 다음 patch 버전을 계산하고 동일한 Windows EXE/Android APK를 두 저장소에 모두 Release합니다. 새 저장소의 index는 새 저장소 asset을 가리키고, 기존 저장소의 index는 동일한 브리지 파일의 기존 저장소 asset을 가리킵니다. 따라서 기존 설치본은 기존 저장소에서 브리지 버전을 정상 업데이트할 수 있고, 브리지 버전이 설치된 다음부터 자체 업데이트 요청은 `astral-party-auto-patcher`로 전환됩니다.

`Migration Bridge`는 한 번만 실행하도록 보호되어 있습니다. 브리지 완료 후 WindowsPatcher/AndroidPatcher의 신규 릴리즈와 원본 Android split APK는 이 저장소에서만 관리합니다. 기존 저장소의 과거 Patcher/APK Release와 distribution metadata는 구버전 설치본 호환을 위해 삭제하지 않습니다.
