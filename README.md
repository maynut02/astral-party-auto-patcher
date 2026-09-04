# Astral Party Auto Patcher

Astral Party 한국어 패치를 설치하는 Windows/Android 클라이언트와 Android 원본 게임 APK 배포 자동화를 관리합니다.

## 구성

- `windows-patcher/` — Windows Steam 글로벌/중국판 패치 설치·제거 클라이언트
- `android-patcher/` — Android INT/CN 패치 설치·복원 및 INT 원본 게임 설치 클라이언트
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

AndroidPatcher는 JDK 17 이상, Gradle 9.4.1, Android SDK 37.0을 사용합니다. Windows에서는 저장소의 로컬 빌드 스크립트로 환경을 준비할 수 있습니다.

```powershell
cd android-patcher
.\setup-local.ps1
.\build-local.ps1
```

필요한 GitHub Actions secrets는 [`.env.example`](.env.example)에 정리되어 있습니다.

## 라이선스

[MIT License](LICENSE)
