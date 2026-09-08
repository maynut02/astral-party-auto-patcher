# Astral Party Auto Patcher

Astral Party 한국어 패치의 Windows BepInEx 플러그인, 기존 Windows 패처, Android 클라이언트와 Android 원본 게임 APK 배포 자동화를 관리합니다.

## 구성

- `windows-plugin/` — 현재 Windows Steam INT/CN용 BepInEx Preloader/Plugin과 배포 ZIP 빌드 ([빌드 안내](windows-plugin/README.md))
- `windows-patcher/` — 기존 WindowsPatcher. 마지막 배포 버전을 보존하며 신규 기능 개발은 하지 않습니다.
- `android-patcher/` — Android INT/CN 패치 설치·복원 및 INT 원본 게임 설치 클라이언트
- `.github/workflows/windows-plugin.yml` — Windows Plugin ZIP 릴리즈
- `.github/legacy-workflows/windows-patcher.yml` — 기존 WindowsPatcher 릴리즈 workflow 보존(실행 비활성화)
- `.github/workflows/android-patcher.yml` — AndroidPatcher 릴리즈
- `.github/workflows/android-game-original.yml` — Google Play 원본 split APK 릴리즈
- `distribution` branch
  - `patcher-index.json` — 기존 WindowsPatcher 업데이트
  - `mobile-patcher-index.json` — AndroidPatcher 업데이트
  - `android-game-index.json` — 원본 Android 게임 APK

패치 manifest와 패치 리소스는 별도 저장소 `maynut02/astral-party-korean-patch`에서 관리합니다. 새 Windows Plugin은 게임 실행 시 해당 저장소의 GitHub Release를 확인하며, 기존 WindowsPatcher와 AndroidPatcher는 기존 배포 프로토콜을 유지합니다.

## 개발

### Windows Plugin

Windows Plugin은 공식 BepInEx 6 IL2CPP 패키지와 Unity base libraries를 빌드 시 받아 SHA-256을 검증한 뒤, Preloader/Plugin을 컴파일하고 최종 ZIP을 조립합니다. 실제 게임 설치본이나 `BepInEx/interop`는 빌드에 필요하지 않습니다.

```powershell
./windows-plugin/scripts/build-package.ps1 -Version 1.0.0
```

CI와 릴리즈 workflow는 `ubuntu-latest`에서 동일한 빌드를 수행합니다.

### WindowsPatcher (legacy)

```bash
cd windows-patcher
cargo fmt --all -- --check
cargo test --locked --all-targets --all-features
cargo clippy --locked --all-targets --all-features -- -D warnings
```

### AndroidPatcher

AndroidPatcher는 JDK 17 이상, Gradle 9.4.1, Android SDK 37.0을 사용합니다. Windows에서는 저장소의 로컬 빌드 스크립트로 환경을 준비할 수 있습니다.

```powershell
cd android-patcher
.\setup-local.ps1
.\build-local.ps1
```

필요한 GitHub Actions secrets는 [`.env.example`](.env.example)에 정리되어 있습니다.

## 라이선스

[MIT License](LICENSE)
