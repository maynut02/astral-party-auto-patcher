# Windows Plugin

Astral Party Windows Steam판용 한국어 패치 런타임과 배포 ZIP을 관리합니다.

기존 `windows-patcher`와 달리 별도 패처 프로그램을 실행하지 않습니다. 사용자는 Release ZIP을 게임 폴더에 한 번 설치하고 이후에는 Steam에서 게임을 평소처럼 실행합니다.

## 구성

- `src/Preloader/` — 게임 시작 전에 최신 릴리스와 리소스를 검증하고 `data.unity3d` 접근을 전환합니다.
- `src/Plugin/` — 같은 실행에서 준비된 Addressables payload를 연결하고 게임 내 상태 오버레이를 표시합니다.
- `src/UnityEngine.UI.Reference/` — 빌드 전용 최소 uGUI reference assembly입니다. 최종 ZIP에는 포함하지 않습니다.
- `config/BepInEx.cfg` — Astral Party의 HybridCLR 시작 문제를 피하기 위해 조정한 BepInEx 설정입니다.
- `packaging/` — 사용자용 `적용방법.txt`를 관리합니다.
- `scripts/build-package.ps1` — 외부 의존성 다운로드, DLL 빌드, 검증, ZIP 생성을 모두 수행합니다.

## 지원 경로

동일한 DLL 두 개가 설치 경로를 기준으로 자동 판별합니다.

- `8vJXnINT` / `AstralParty_INT.exe` → `INT_STEAM`
- `8vJXn6CN` / `AstralParty_CN.exe` → `CN_STEAM`

## 재현 가능한 빌드

저장소에는 BepInEx 바이너리나 게임에서 생성한 `BepInEx/interop` 파일을 저장하지 않습니다.

빌드 시 다음 고정 의존성을 내려받고 SHA-256을 검증합니다.

- BepInEx `6.0.0-be.788+5b766a3` Unity IL2CPP Windows x64
- BepInEx Unity base libraries `2022.3.62`
- BepInEx LGPL-2.1 라이선스

Plugin의 Unity 참조는 공식 Unity base libraries와 `src/UnityEngine.UI.Reference`의 컴파일 전용 API surface로 해결합니다. 따라서 실제 Astral Party 설치본이나 생성된 `BepInEx/interop`가 Actions 빌드에 필요하지 않습니다.

## 로컬/CI 빌드

.NET 6 SDK와 PowerShell 7이 있으면 Windows뿐 아니라 Linux에서도 실행할 수 있습니다.

```powershell
./windows-plugin/scripts/build-package.ps1 -Version 1.0.0
```

결과:

```text
windows-plugin/dist/
├─ AstralWindowsPlugin-v1.0.0.zip
└─ windows-plugin-build.json
```

ZIP에는 공식 BepInEx 런타임, 조정된 `BepInEx.cfg`, Preloader, Plugin, 사용자 안내 및 BepInEx 라이선스가 포함됩니다. 빌드 전용 Unity reference 파일과 실행 후 생성되는 캐시는 포함하지 않습니다.

## GitHub Actions

- `CI`의 `windows-plugin` job은 `ubuntu-latest`에서 패키지를 실제로 빌드합니다.
- `WindowsPlugin` workflow는 수동으로 패키지 버전을 선택해 `windows-plugin-vX.Y.Z` immutable Release를 생성합니다.
- Plugin 자체 업데이트 인덱스는 운영하지 않습니다. 코드 업데이트가 필요한 경우 새 ZIP Release를 배포합니다.
