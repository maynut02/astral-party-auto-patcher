# Windows Patcher

기존 Windows 자동 패치 프로그램의 소스입니다. 현재 버전은 `1.3.1`입니다.

프로그램을 실행하면 `distribution/patcher-index.json`의 버전과 현재 버전을 비교합니다. 더 최신 버전이 있으면 실행 파일을 다운로드하고 크기와 SHA-256을 확인한 뒤, 기존 프로세스가 종료된 다음 설치된 프로그램을 교체하여 다시 실행합니다. 웹사이트에서 전달한 `astral://` 작업도 재실행 시 그대로 이어집니다. 업데이트 확인이나 다운로드에 실패하면 현재 버전으로 계속 실행합니다.

프로그램 업데이트 정보는 `maynut02/astral-party-auto-patcher`에서, 게임 한글패치 정보는 `maynut02/astral-party-korean-patch`에서 각각 받습니다.

Steam 글로벌(`INT_STEAM`)과 중국(`CN_STEAM`) 버전 모두 게임 카탈로그의 번들 이름과 해시를 기준으로 패치 위치를 선택합니다.

- CDN 번들은 Steam 게임 데이터 폴더의 `com.unity.addressables/AssetBundles/<번들 이름>/<해시>/__data`를 우선 사용합니다. 게임 데이터 폴더에 캐시가 없는 기존 클라이언트에서는 LocalLow의 같은 캐시 경로를 사용합니다.
- `Addressables.RuntimePath` 번들은 Steam 게임 데이터 폴더의 `StreamingAssets/aa/` 아래 파일을 사용합니다.
- 설치 기록, 중단된 설치 복구, 패치 제거도 실제로 선택한 위치를 사용합니다. 제거 시 배포된 원본 파일을 검증하여 복원합니다.

폰트가 업데이트마다 CDN과 게임 내장 번들 사이를 이동해도 같은 방식으로 판단합니다. 카탈로그가 없거나 번들 이름·해시가 일치하지 않으면 파일을 변경하지 않고 오류를 표시합니다. 게임 업데이트 후에는 게임을 한 번 실행하여 최신 카탈로그와 CDN 파일을 받은 뒤 패치 프로그램을 실행하세요.

카탈로그 JSON과 해시 파일은 LocalLow에서 읽습니다. 이 카탈로그의 위치와 실제 CDN 번들 캐시의 위치는 서로 다를 수 있습니다. 설치 로그에는 카탈로그·번들 캐시·게임 데이터 경로를 각각 기록합니다.

Steam 설치 폴더의 교체 대상은 선택한 배포 원본, 적용할 패치 또는 같은 설치 위치·카탈로그에 기록된 이전 패치와 해시가 일치하는지도 먼저 검사합니다. 업데이트된 클라이언트에 옛 카탈로그가 남아 있는 경우에도 서로 다른 버전의 게임 파일을 덮어쓰지 않습니다.

GitHub Actions의 `WindowsPatcher` 워크플로에서 `Run workflow`를 누르고 `bump`(`patch`/`minor`/`major`)를 선택하면 테스트·빌드·릴리스와 자동 업데이트 인덱스 갱신까지 진행합니다. 기존 릴리스·배포 인덱스·Cargo 버전 중 가장 높은 버전을 기준으로 선택한 단위를 증가시키며, 빌드에서 사용하는 `Cargo.toml`과 `Cargo.lock` 버전을 함께 맞춥니다. EXE의 버전·메타데이터·SHA-256·크기를 확인한 뒤 배포하고, 인덱스는 릴리스 생성에 성공한 후에만 갱신합니다.

워크플로는 `windows-patcher-v<버전>` GitHub 릴리스에 `AstralWindowsPatcher.exe`를 올리고 `distribution` 브랜치의 `patcher-index.json`을 다음 형식으로 생성합니다.

```json
{
  "schemaVersion": 1,
  "version": "1.3.1",
  "downloadUrl": "https://github.com/maynut02/astral-party-auto-patcher/releases/download/windows-patcher-v1.3.1/AstralWindowsPatcher.exe",
  "sha256": "<실행 파일의 SHA-256>",
  "size": 1234567
}
```

`sha256`과 `size`는 실제 배포한 실행 파일 값으로 채웁니다. 새 버전은 기존 배포 인덱스의 `1.3.0`보다 높게 지정하여 이전 배포로 되돌아가지 않도록 합니다. 업데이트 기능이 제거된 기존 프로그램은 이번 버전을 한 번 직접 실행해야 합니다.

저장소 루트에서 다음 명령으로 검증하고 빌드할 수 있습니다.

```powershell
cargo fmt --manifest-path windows-patcher/Cargo.toml --all -- --check
cargo test --manifest-path windows-patcher/Cargo.toml --locked --all-targets --all-features
cargo clippy --manifest-path windows-patcher/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo build --manifest-path windows-patcher/Cargo.toml --locked --release --bin AstralAutoPatcher
```

실행 파일: `windows-patcher/target/release/AstralAutoPatcher.exe`.
