from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]


def _text(name: str) -> str:
    return (ROOT / ".github/workflows" / name).read_text(encoding="utf-8")


def test_windows_release_reads_patch_index_from_patch_repo() -> None:
    text = _text("windows-patcher.yml")
    assert "maynut02/astral-party-korean-patch/distribution/release-index.json" in text
    assert "distribution/patcher-index.json" in text
    assert "windows-patcher-v" in text


def test_android_release_owns_mobile_index() -> None:
    text = _text("android-patcher.yml")
    assert "mobile-patcher-index.json" in text
    assert "android-patcher-v" in text


def test_original_apk_workflow_never_merges_or_signs_game_apks() -> None:
    workflow = _text("android-game-original.yml")
    parsed = yaml.safe_load(workflow)
    assert "release" in parsed["jobs"]
    assert "split_apk=true" in workflow
    assert "prepare_original_apks.py" in workflow
    assert "APKEditor" not in workflow
    assert "apksigner sign" not in workflow
    assert "install-multiple" not in workflow


def test_runtime_repository_boundaries_are_split() -> None:
    windows = (ROOT / "windows-patcher/src/cli.rs").read_text(encoding="utf-8")
    android = (ROOT / "android-patcher/app/src/main/java/com/maynutlab/astralpatcher/core/PatchProtocol.kt").read_text(encoding="utf-8")
    assert "astral-party-korean-patch/distribution/release-index.json" in windows
    assert "astral-party-auto-patcher/distribution/patcher-index.json" in windows
    assert "astral-party-auto-patcher/releases/download" in windows
    assert "astral-party-korean-patch/distribution/release-index.json" in android
    assert "astral-party-auto-patcher/distribution/mobile-patcher-index.json" in android
    assert "astral-party-auto-patcher/distribution/android-game-index.json" in android
