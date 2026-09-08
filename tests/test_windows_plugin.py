from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "windows-plugin"


def test_windows_plugin_replaces_abandoned_desktop_project() -> None:
    assert not (ROOT / "windows-desktop").exists()
    assert (ROOT / "windows-patcher").is_dir()
    assert (PLUGIN / "src/Preloader/DataUnity3dRedirect.cs").is_file()
    assert (PLUGIN / "src/Plugin/AddressablesInProcessPatch.cs").is_file()


def test_windows_plugin_build_has_no_game_install_dependency() -> None:
    projects = "\n".join(
        path.read_text(encoding="utf-8")
        for path in [
            PLUGIN / "src/Preloader/AstralParty.DataUnity3dRedirect.csproj",
            PLUGIN / "src/Plugin/AstralParty.AddressablesInProcessPatch.csproj",
            PLUGIN / "src/UnityEngine.UI.Reference/UnityEngine.UI.Reference.csproj",
        ]
    )
    assert "GameRoot" not in projects
    assert "BepInEx/interop" not in projects
    assert "BepInEx\\interop" not in projects
    assert "csc.exe" not in projects
    assert "AstralDepsRoot" in projects


def test_packager_pins_and_verifies_external_dependencies() -> None:
    script = (PLUGIN / "scripts/build-package.ps1").read_text(encoding="utf-8")
    assert "6.0.0-be.788%2B5b766a3.zip" in script
    assert "f4cc496bd098a0df4164b81e3737297707f13a47c2478dba2f60eefab784817a" in script
    assert "2022.3.62.zip" in script
    assert "575e7d600f69de8200ccf4db700b3ae6252366c22e8c3434c860e428974518d1" in script
    assert "BepInEx/BepInEx/5b766a3/LICENSE" in script
    assert "Assert-Sha256" in script
    assert "UnityEngine.UI.Reference" not in "\n".join(
        line for line in script.splitlines() if "requiredPackageFiles" in line
    )


def test_package_contains_only_runtime_plugin_outputs() -> None:
    script = (PLUGIN / "scripts/build-package.ps1").read_text(encoding="utf-8")
    assert "BepInEx/patchers/AstralParty.DataUnity3dRedirect.dll" in script
    assert "BepInEx/plugins/AstralPartyKoreanPatch/AstralParty.AddressablesInProcessPatch.dll" in script
    assert "BepInEx/interop" in script  # explicitly forbidden from the final package
    assert "LICENSE-BepInEx.txt" in script
    assert "Read-PreloaderVersion" in script
    assert "Read-PluginVersion" in script


def test_tuned_bepinex_config_keeps_required_settings() -> None:
    config = (PLUGIN / "config/BepInEx.cfg").read_text(encoding="utf-8")
    assert "UnityLogListening = false" in config
    assert "Enabled = false" in config
    assert "WriteUnityLog = false" in config


def test_windows_plugin_release_builds_on_linux() -> None:
    workflow_path = ROOT / ".github/workflows/windows-plugin.yml"
    workflow = workflow_path.read_text(encoding="utf-8")
    parsed = yaml.safe_load(workflow)
    release = parsed["jobs"]["release"]
    assert release["runs-on"] == "ubuntu-latest"
    assert "actions/setup-dotnet@v5" in workflow
    assert "6.0.x" in workflow
    assert "build-package.ps1" in workflow
    assert "windows-plugin-v" in workflow
    assert "patcher-index.json" not in workflow


def test_plugin_uses_compile_only_ui_reference() -> None:
    source = (PLUGIN / "src/Plugin/AddressablesInProcessPatch.cs").read_text(encoding="utf-8")
    project = (PLUGIN / "src/Plugin/AstralParty.AddressablesInProcessPatch.csproj").read_text(
        encoding="utf-8"
    )
    ui_reference = (PLUGIN / "src/UnityEngine.UI.Reference/UnityEngine.UI.Reference.cs").read_text(
        encoding="utf-8"
    )
    assert "using UnityEngine.EventSystems;" not in source
    assert "UnityEngine.UI.Reference.csproj" in project
    assert "Compile-only API surface" in ui_reference
    assert "Resources.FindObjectsOfTypeAll<Font>()" not in source
    assert "MakeGenericMethod(typeof(Font)).Invoke" in source


def test_preloader_restores_game_window_foreground_once() -> None:
    source = (PLUGIN / "src/Preloader/DataUnity3dRedirect.cs").read_text(encoding="utf-8")
    assert "WsExNoActivate" in source
    assert "SwShowNoActivate" in source
    assert "ScheduleGameForegroundRestore" in source
    assert '"UnityWndClass"' in source
    assert "TryActivateGameWindow" in source
    assert "AttachThreadInput" in source
    assert "SetForegroundWindow(gameWindow)" in source
    assert "HwndTopmost" in source
    assert "HwndNoTopmost" in source


def test_overlay_close_glyph_uses_release_safe_image_icon() -> None:
    source = (PLUGIN / "src/Plugin/AddressablesInProcessPatch.cs").read_text(encoding="utf-8")
    assert "CreateCloseGlyphBar" in source
    assert "GetSolidSprite" in source
    assert "texture.SetPixel(0, 0, Color.white)" in source
    assert "texture.SetPixels(" not in source
    assert 'glyph.text = "×"' not in source
