# Windows Patcher

기존 Windows 자동 패치 프로그램의 소스입니다. 이번 호환성 수정 버전은 `0.8.9`입니다.

Steam 글로벌(`INT_STEAM`)과 중국(`CN_STEAM`) 버전 모두 게임 카탈로그의 번들 이름과 해시를 기준으로 패치 위치를 선택합니다.

- CDN 번들은 기존 LocalLow의 `com.unity.addressables/AssetBundles/<번들 이름>/<해시>/__data`를 사용합니다.
- `Addressables.RuntimePath` 번들은 Steam 게임 데이터 폴더의 `StreamingAssets/aa/` 아래 파일을 사용합니다.
- 설치 기록, 중단된 설치 복구, 패치 제거도 실제로 선택한 위치를 사용합니다. 제거 시 배포된 원본 파일을 검증하여 복원합니다.

폰트가 업데이트마다 CDN과 게임 내장 번들 사이를 이동해도 같은 방식으로 판단합니다. 카탈로그가 없거나 번들 이름·해시가 일치하지 않으면 파일을 변경하지 않고 오류를 표시합니다. 게임 업데이트 후에는 게임을 한 번 실행하여 최신 카탈로그와 CDN 파일을 받은 뒤 패치 프로그램을 실행하세요.

Steam 설치 폴더의 교체 대상은 선택한 배포 원본, 적용할 패치 또는 같은 설치 위치·카탈로그에 기록된 이전 패치와 해시가 일치하는지도 먼저 검사합니다. 업데이트된 클라이언트에 옛 카탈로그가 남아 있는 경우에도 서로 다른 버전의 게임 파일을 덮어쓰지 않습니다.

이 수정은 Windows 자동 릴리스 워크플로를 재개하지 않습니다.

저장소 루트에서 다음 명령으로 검증하고 빌드할 수 있습니다.

```powershell
cargo fmt --manifest-path windows-patcher/Cargo.toml --all -- --check
cargo test --manifest-path windows-patcher/Cargo.toml --locked --all-targets --all-features
cargo clippy --manifest-path windows-patcher/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo build --manifest-path windows-patcher/Cargo.toml --locked --release --bin AstralAutoPatcher
```

실행 파일: `windows-patcher/target/release/AstralAutoPatcher.exe`.
