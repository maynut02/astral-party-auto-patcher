from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/workflow/release_notes.py"
SPEC = importlib.util.spec_from_file_location("release_notes", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_windows_patcher_notes_include_artifact_hash() -> None:
    text = MODULE.render_windows_patcher_notes(
        version="0.8.8", sha256="a" * 64,
        repository="owner/repo", run_id="10", run_number="2",
    )
    assert "Windows x64" in text
    assert "AstralWindowsPatcher.exe" in text
    assert "a" * 64 in text


def test_android_patcher_notes_include_requirements() -> None:
    text = MODULE.render_android_patcher_notes(
        version="0.1.0", version_code="1000", sha256="c" * 64, size="123456",
        repository="owner/repo", run_id="12", run_number="4",
    )
    assert "Android 11" in text
    assert "Shizuku" in text
    assert "AstralAndroidPatcher.apk" in text


def test_android_apk_notes_include_original_metadata() -> None:
    text = MODULE.render_android_apk_notes(
        version="3.2.0", version_code="555", file_count="2", device_profile="px_9a",
        certificate_sha256="d" * 64, repository="owner/repo", run_id="13", run_number="5",
    )
    assert text.startswith("## Astral Party APK 원본\n")
    assert "`3.2.0` (`versionCode 555`)" in text
    assert "`2개`" in text
    assert "d" * 64 in text
