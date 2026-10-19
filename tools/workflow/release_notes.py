from __future__ import annotations

import argparse
from pathlib import Path


def _run_link(repository: str, run_id: str, run_number: str) -> str:
    return f"[GitHub Actions #{run_number}](https://github.com/{repository}/actions/runs/{run_id})"


def render_windows_patcher_notes(*, version: str, sha256: str, repository: str, run_id: str, run_number: str) -> str:
    return "\n".join([
        "## WindowsPatcher", "", f"- 버전: `{version}`", "- 대상: Windows x64",
        "- Steam 글로벌/중국판 한글패치 설치와 제거를 지원합니다.", "", "## 사용 방법", "",
        "- `AstralWindowsPatcher.exe`를 내려받아 바로 실행하면 됩니다.", "", "## 파일 확인", "",
        "- 파일: `AstralWindowsPatcher.exe`", f"- SHA-256: `{sha256}`", "", "## 빌드", "",
        f"- {_run_link(repository, run_id, run_number)}", "",
    ])


def render_android_patcher_notes(*, version: str, version_code: str, sha256: str, size: str, repository: str, run_id: str, run_number: str) -> str:
    return "\n".join([
        "## AndroidPatcher", "", f"- 버전: `{version}` (`versionCode {version_code}`)", "- 대상: Android 11 이상",
        "- Google Play 원본 게임을 유지하고 Shizuku로 외부 Addressables 캐시만 패치합니다.",
        "- LANG, STR, TMP 폰트 bundle을 현재 게임 catalog에 맞춰 검증·백업·교체합니다.",
        "- APK 내부 legacy 폰트는 수정하지 않습니다.", "", "## 설치 전 확인", "",
        "- Shizuku를 설치하고 무선 디버깅 또는 ADB로 Shizuku 서비스를 시작해야 합니다.",
        "- 원본 게임을 한 번 실행해 최신 리소스 다운로드를 완료해야 합니다.",
        "- AndroidPatcher에 Shizuku 권한을 허용해야 게임 캐시를 패치할 수 있습니다.", "", "## 파일 확인", "",
        "- 파일: `AstralAndroidPatcher.apk`", f"- 크기: `{size}` bytes", f"- SHA-256: `{sha256}`", "", "## 빌드", "",
        f"- {_run_link(repository, run_id, run_number)}", "",
    ])


def render_android_apk_notes(*, version: str, version_code: str, file_count: str, device_profile: str, certificate_sha256: str, repository: str, run_id: str, run_number: str) -> str:
    return "\n".join([
        "## Astral Party APK 원본", "", "- 플랫폼: `INT_ANDROID`",
        f"- 버전: `{version}` (`versionCode {version_code}`)", f"- 구성: Google Play split APK `{file_count}개`",
        f"- 기기 프로필: `{device_profile}`", "", "## 파일 확인", "",
        f"- APK 인증서 SHA-256: `{certificate_sha256}`", "", "## 빌드", "",
        f"- {_run_link(repository, run_id, run_number)}", "",
    ])


def _write(path: str, text: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8", newline="\n")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Generate concise GitHub release notes")
    sub = parser.add_subparsers(dest="kind", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--output", required=True)
    common.add_argument("--repository", required=True)
    common.add_argument("--run-id", required=True)
    common.add_argument("--run-number", required=True)

    windows = sub.add_parser("windows-patcher", parents=[common])
    windows.add_argument("--version", required=True)
    windows.add_argument("--sha256", required=True)

    android = sub.add_parser("android-patcher", parents=[common])
    android.add_argument("--version", required=True)
    android.add_argument("--version-code", required=True)
    android.add_argument("--sha256", required=True)
    android.add_argument("--size", required=True)

    apk = sub.add_parser("android-apk", parents=[common])
    apk.add_argument("--version", required=True)
    apk.add_argument("--version-code", required=True)
    apk.add_argument("--file-count", required=True)
    apk.add_argument("--device-profile", required=True)
    apk.add_argument("--certificate-sha256", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    common = dict(repository=args.repository, run_id=args.run_id, run_number=args.run_number)
    if args.kind == "windows-patcher":
        text = render_windows_patcher_notes(version=args.version, sha256=args.sha256, **common)
    elif args.kind == "android-patcher":
        text = render_android_patcher_notes(version=args.version, version_code=args.version_code, sha256=args.sha256, size=args.size, **common)
    elif args.kind == "android-apk":
        text = render_android_apk_notes(version=args.version, version_code=args.version_code, file_count=args.file_count, device_profile=args.device_profile, certificate_sha256=args.certificate_sha256, **common)
    else:
        raise ValueError(f"unsupported release note kind: {args.kind}")
    _write(args.output, text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
