from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]


def _text(name: str) -> str:
    return (ROOT / ".github/workflows" / name).read_text(encoding="utf-8")


def test_windows_patcher_release_workflow_is_archived() -> None:
    active = ROOT / ".github/workflows/windows-patcher.yml"
    archived = ROOT / ".github/legacy-workflows/windows-patcher.yml"
    assert not active.exists()
    assert archived.is_file()

    text = archived.read_text(encoding="utf-8")
    assert "maynut02/astral-party-korean-patch/distribution/release-index.json" in text
    assert "--path patcher-index.json" in text
    assert "windows-patcher-v" in text
    assert "gh release view patcher-index" not in text
    assert "gh release upload patcher-index" not in text


def test_windows_plugin_release_uses_version_bump() -> None:
    text = _text("windows-plugin.yml")
    assert "description: Version bump" in text
    assert "- patch" in text
    assert "- minor" in text
    assert "- major" in text
    assert "Resolve next Windows Plugin version" in text
    assert "^windows-plugin-v" in text
    assert "No existing Windows Plugin release found; starting at 1.0.0." in text
    assert "steps.version.outputs.version" in text
    assert "steps.version.outputs.tag" in text
    assert '--title "WindowsPlugin v$env:PACKAGE_VERSION"' in text
    assert "release_notes.py windows-plugin" in text


def test_android_release_owns_mobile_index() -> None:
    text = _text("android-patcher.yml")
    assert "mobile-patcher-index.json" in text
    assert "android-patcher-v" in text


def test_original_apk_workflow_never_merges_or_signs_game_apks() -> None:
    workflow = _text("android-game-original.yml")
    parsed = yaml.safe_load(workflow)
    assert parsed["name"] == "INT_ANDROID APK"
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
    assert "astral-party-auto-patcher/distribution/patcher-index.json" not in windows
    assert "astral-party-auto-patcher/releases/download" not in windows
    assert "astral-party-korean-patch/distribution/release-index.json" in android
    assert "astral-party-auto-patcher/distribution/mobile-patcher-index.json" in android
    assert "astral-party-auto-patcher/distribution/android-game-index.json" in android
