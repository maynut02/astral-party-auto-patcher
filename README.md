# Astral Party Auto Patcher

Astral Party 한국어 패치의 기존 Windows 패처, Android 클라이언트와 Android 원본 게임 APK 배포 자동화를 관리합니다.

Windows Steam판용 BepInEx 한글패치 플러그인은 독립 프로젝트 `C:\Users\Home\Documents\Works\AstralParty\astral-party-korean-plugin`으로 분리되었습니다. 플러그인 개발과 빌드·패키징은 해당 프로젝트에서 관리합니다.

## 구성

- `windows-patcher/` — Steam 글로벌/중국판 한글패치 설치·복원 및 프로그램 자동 업데이트
- `android-patcher/` — Android INT/CN 패치 설치·복원 및 INT 원본 게임 설치 클라이언트
- `.github/workflows/windows-patcher.yml` — WindowsPatcher 빌드·릴리스 및 자동 업데이트 인덱스 갱신
- `.github/workflows/android-patcher.yml` — AndroidPatcher 릴리즈
- `.github/workflows/android-game-original.yml` — Google Play 원본 split APK 릴리즈
- `distribution` branch
  - `patcher-index.json` — 기존 WindowsPatcher 업데이트
  - `mobile-patcher-index.json` — AndroidPatcher 업데이트
  - `android-game-index.json` — 원본 Android 게임 APK

패치 manifest와 패치 리소스는 별도 저장소 `maynut02/astral-party-korean-patch`에서 관리합니다. 기존 WindowsPatcher와 AndroidPatcher는 기존 배포 프로토콜을 유지합니다.

## 개발

### WindowsPatcher

```bash
cd windows-patcher
cargo fmt --all -- --check
cargo test --locked --all-targets --all-features
cargo clippy --locked --all-targets --all-features -- -D warnings
```

배포는 GitHub Actions의 `WindowsPatcher` → `Run workflow`에서 `bump`(`patch`/`minor`/`major`)를 선택해 실행합니다. 기존 릴리스·배포 인덱스·Cargo 버전 중 가장 높은 버전을 기준으로 다음 버전을 정하고, 검증한 `AstralWindowsPatcher.exe`를 새 릴리스에 올린 뒤 `distribution/patcher-index.json`을 갱신합니다. 실행 중인 프로그램은 이 인덱스를 통해 새 버전을 받습니다.

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
