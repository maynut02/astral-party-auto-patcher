from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/android/prepare_original_apks.py"


def _module():
    spec = importlib.util.spec_from_file_location("prepare_original_apks", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_prepare_preserves_apk_bytes_and_uses_auto_patcher_release_urls(tmp_path, monkeypatch) -> None:
    module = _module()
    input_dir = tmp_path / "input"
    output_dir = tmp_path / "output"
    input_dir.mkdir()
    base = input_dir / "downloaded-base.apk"
    split = input_dir / "downloaded-arm64.apk"
    base.write_bytes(b"base-google-play-apk")
    split.write_bytes(b"split-google-play-apk")

    def inspect(path, _aapt, _apksigner):
        return module.ApkMetadata(
            source=path,
            package_name=module.PACKAGE_NAME,
            version_name="1.2.3",
            version_code=1234,
            split_name=None if path == base else "config.arm64_v8a",
            certificate_sha256="a" * 64,
            sha256=module._sha256(path),
            size=path.stat().st_size,
        )

    monkeypatch.setattr(module, "_inspect", inspect)
    payload = module.prepare(input_dir, output_dir, "aapt", "apksigner", "px_9a")
    assert payload["releaseTag"] == "android-game-v1234"
    assert all("maynut02/astral-party-auto-patcher/releases/download/android-game-v1234/" in item["downloadUrl"] for item in payload["files"])
    written = json.loads((output_dir / "AstralPartyOriginal.json").read_text(encoding="utf-8"))
    assert written == payload
