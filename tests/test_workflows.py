from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]


def _text(name: str) -> str:
    return (ROOT / ".github/workflows" / name).read_text(encoding="utf-8")


def test_windows_patcher_release_workflow_builds_and_updates_distribution() -> None:
    active = ROOT / ".github/workflows/windows-patcher.yml"
    archived = ROOT / ".github/legacy-workflows/windows-patcher.yml"
    assert active.is_file()
    assert not archived.exists()

    text = active.read_text(encoding="utf-8")
    workflow = yaml.safe_load(text)
    trigger = workflow.get("on", workflow.get(True))
    assert set(trigger) == {"workflow_dispatch"}
    assert trigger["workflow_dispatch"]["inputs"]["bump"]["options"] == ["patch", "minor", "major"]
    assert workflow["permissions"]["contents"] == "write"
    assert workflow["concurrency"]["cancel-in-progress"] is False
    job = workflow["jobs"]["build"]
    assert job["runs-on"] == "windows-latest"
    assert job["defaults"]["run"]["working-directory"] == "windows-patcher"
    assert "maynut02/astral-party-korean-patch/distribution/release-index.json" in text
    assert job["env"]["ASTRAL_PATCHER_INDEX_URL"].endswith("astral-party-auto-patcher/distribution/patcher-index.json")
    assert job["env"]["ASTRAL_PATCHER_RELEASE_BASE_URL"].endswith("astral-party-auto-patcher/releases/download")
    commands = {step.get("name"): step.get("run", "") for step in job["steps"]}
    assert commands["Rust format"] == "cargo fmt --all -- --check"
    assert commands["Rust tests"] == "cargo test --locked --all-targets --all-features"
    assert commands["Rust clippy"] == "cargo clippy --locked --all-targets --all-features -- -D warnings"
    assert commands["Build portable EXE"] == "cargo build --locked --release --bin AstralAutoPatcher"
    names = list(commands)
    assert names.index("Verify EXE") < names.index("Publish immutable WindowsPatcher release")
    assert names.index("Publish immutable WindowsPatcher release") < names.index("Update WindowsPatcher distribution index")
    assert "--target $env:GITHUB_SHA" in commands["Publish immutable WindowsPatcher release"]
    assert "--path patcher-index.json" in text
    assert "windows-patcher-v" in text
    assert "gh release view patcher-index" not in text
    assert "gh release upload patcher-index" not in text


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
    assert "astral-party-auto-patcher/distribution/patcher-index.json" in windows
    assert "astral-party-auto-patcher/releases/download" in windows
    assert "astral-party-korean-patch/distribution/release-index.json" in android
    assert "astral-party-auto-patcher/distribution/mobile-patcher-index.json" in android
    assert "astral-party-auto-patcher/distribution/android-game-index.json" in android
