#!/usr/bin/env python3
"""Focused static validation for the Evolution-X edge-light customization contract."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

FILES = {
    "Settings.java": ROOT / "core/java/android/provider/Settings.java",
    "EdgeLightSettingsRepository.kt": ROOT / "packages/SystemUI/src/com/android/systemui/edgelight/EdgeLightSettingsRepository.kt",
    "EdgeLightView.kt": ROOT / "packages/SystemUI/src/com/android/systemui/edgelight/EdgeLightView.kt",
    "EdgeLightViewController.kt": ROOT / "packages/SystemUI/src/com/android/systemui/edgelight/EdgeLightViewController.kt",
}

texts = {name: path.read_text(encoding="utf-8") for name, path in FILES.items()}
checks: list[tuple[bool, str, str]] = []


def add(ok: bool, description: str, file_name: str) -> None:
    checks.append((ok, description, file_name))


settings = texts["Settings.java"]
repo = texts["EdgeLightSettingsRepository.kt"]
view = texts["EdgeLightView.kt"]
controller = texts["EdgeLightViewController.kt"]

required_settings = {
    "EDGE_LIGHT_POSITION_TOP": "edge_light_position_top",
    "EDGE_LIGHT_POSITION_SIDES": "edge_light_position_sides",
    "EDGE_LIGHT_POSITION_BOTTOM": "edge_light_position_bottom",
    "EDGE_LIGHT_SCREEN_ON": "edge_light_screen_on",
    "EDGE_LIGHT_SCREEN_OFF": "edge_light_screen_off",
    "EDGE_LIGHT_AOD": "edge_light_aod",
    "EDGE_LIGHT_AURORA_COLOR_MODE": "edge_light_aurora_color_mode",
}
for constant, value in required_settings.items():
    add(
        f'public static final String {constant} = "{value}";' in settings,
        f"{constant} is declared with the expected key",
        "Settings.java",
    )

for token in (
    "val positionTop: Boolean",
    "val positionSides: Boolean",
    "val positionBottom: Boolean",
    "val runScreenOn: Boolean",
    "val runScreenOff: Boolean",
    "val runAod: Boolean",
    "val auroraColorMode: String",
    "Settings.System.EDGE_LIGHT_POSITION_TOP",
    "Settings.System.EDGE_LIGHT_SCREEN_ON",
    "Settings.System.EDGE_LIGHT_AURORA_COLOR_MODE",
):
    add(token in repo, f"Settings repository contains {token}", "EdgeLightSettingsRepository.kt")

for token in (
    "var positionTop: Boolean",
    "var positionSides: Boolean",
    "var positionBottom: Boolean",
    "var auroraColorMode: String",
    'const val EFFECT_AURORA = "aurora"',
    "drawAurora(canvas)",
    "ComposeShader",
    "clipToEnabledEdges",
):
    add(token in view, f"Renderer contains {token}", "EdgeLightView.kt")

for token in (
    "listener.addNotificationHandler(this)",
    "settingsRepo.settingsFlow.collectLatest",
    "DisplayMode.SCREEN_ON",
    "DisplayMode.SCREEN_OFF",
    "DisplayMode.AOD",
    "currentSettings.runScreenOn",
    "currentSettings.runScreenOff",
    "currentSettings.runAod",
    "edgeLightView.positionTop = settings.positionTop",
    "edgeLightView.auroraColorMode = settings.auroraColorMode",
):
    add(token in controller, f"Controller contains {token}", "EdgeLightViewController.kt")

failed = [item for item in checks if not item[0]]
for ok, description, file_name in checks:
    print(f"[{'OK' if ok else 'FAIL'}] {description} :: {file_name}")

if failed:
    print(f"\n{len(failed)} edge-light validation check(s) failed.", file=sys.stderr)
    raise SystemExit(1)

print(f"\nAll {len(checks)} edge-light validation checks passed.")
