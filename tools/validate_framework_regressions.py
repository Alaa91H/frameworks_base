#!/usr/bin/env python3
"""Focused static regression checks for local frameworks/base customizations."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

ACTIVITY_THREAD = ROOT / "core/java/android/app/ActivityThread.java"
QR_CONTROLLER = (
    ROOT
    / "packages/SystemUI/src/com/android/systemui/qrcodescanner/controller/QRCodeScannerController.java"
)
SYSTEM_ISLAND = (
    ROOT
    / "packages/SystemUI/src/com/android/systemui/axdynamicbar/data/source/SystemIslandManager.kt"
)
SETTINGS = ROOT / "core/java/android/provider/Settings.java"
WINDOW = ROOT / "core/java/android/view/Window.java"
WINDOW_MANAGER_SERVICE = (
    ROOT / "services/core/java/com/android/server/wm/WindowManagerService.java"
)
WINDOW_STATE = ROOT / "services/core/java/com/android/server/wm/WindowState.java"
ROOT_WINDOW_CONTAINER = (
    ROOT / "services/core/java/com/android/server/wm/RootWindowContainer.java"
)
WINDOW_STATE_TESTS = (
    ROOT / "services/tests/wmtests/src/com/android/server/wm/WindowStateTests.java"
)
EDGE_LIGHT_REPOSITORY = (
    ROOT / "packages/SystemUI/src/com/android/systemui/edgelight/EdgeLightSettingsRepository.kt"
)
EDGE_LIGHT_VIEW = (
    ROOT / "packages/SystemUI/src/com/android/systemui/edgelight/EdgeLightView.kt"
)
EDGE_LIGHT_CONTROLLER = (
    ROOT / "packages/SystemUI/src/com/android/systemui/edgelight/EdgeLightViewController.kt"
)

texts = {
    "ActivityThread.java": ACTIVITY_THREAD.read_text(encoding="utf-8"),
    "QRCodeScannerController.java": QR_CONTROLLER.read_text(encoding="utf-8"),
    "SystemIslandManager.kt": SYSTEM_ISLAND.read_text(encoding="utf-8"),
    "Settings.java": SETTINGS.read_text(encoding="utf-8"),
    "Window.java": WINDOW.read_text(encoding="utf-8"),
    "WindowManagerService.java": WINDOW_MANAGER_SERVICE.read_text(encoding="utf-8"),
    "WindowState.java": WINDOW_STATE.read_text(encoding="utf-8"),
    "RootWindowContainer.java": ROOT_WINDOW_CONTAINER.read_text(encoding="utf-8"),
    "WindowStateTests.java": WINDOW_STATE_TESTS.read_text(encoding="utf-8"),
    "EdgeLightSettingsRepository.kt": EDGE_LIGHT_REPOSITORY.read_text(encoding="utf-8"),
    "EdgeLightView.kt": EDGE_LIGHT_VIEW.read_text(encoding="utf-8"),
    "EdgeLightViewController.kt": EDGE_LIGHT_CONTROLLER.read_text(encoding="utf-8"),
}

checks = []
GOOGLE_QR_ACTIVITY = (
    "com.google.android.gms.mlkit.barcode.ui.PlatformBarcodeScanningActivityProxy"
)


def add(ok: bool, description: str, file_name: str) -> None:
    checks.append((ok, description, file_name))


def braced_range(text: str, anchor: str) -> tuple[int, int]:
    anchor_pos = text.find(anchor)
    if anchor_pos < 0:
        raise ValueError(f"Anchor not found: {anchor}")
    open_pos = text.find("{", anchor_pos)
    if open_pos < 0:
        raise ValueError(f"Opening brace not found after: {anchor}")

    depth = 0
    for pos in range(open_pos, len(text)):
        ch = text[pos]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return open_pos, pos
    raise ValueError(f"Unbalanced block after: {anchor}")


def braced_block(text: str, anchor: str) -> str:
    start, end = braced_range(text, anchor)
    return text[start + 1 : end]


activity = texts["ActivityThread.java"]
qr = texts["QRCodeScannerController.java"]
island = texts["SystemIslandManager.kt"]
settings = texts["Settings.java"]
window = texts["Window.java"]
wms = texts["WindowManagerService.java"]
window_state = texts["WindowState.java"]
root_window = texts["RootWindowContainer.java"]
window_state_tests = texts["WindowStateTests.java"]
edge_repo = texts["EdgeLightSettingsRepository.kt"]
edge_view = texts["EdgeLightView.kt"]
edge_controller = texts["EdgeLightViewController.kt"]

# Edge light regions, display states, and Aurora renderer.
for key in (
    "EDGE_LIGHT_TOP_ENABLED",
    "EDGE_LIGHT_SIDES_ENABLED",
    "EDGE_LIGHT_BOTTOM_ENABLED",
    "EDGE_LIGHT_SCREEN_ON_ENABLED",
    "EDGE_LIGHT_SCREEN_OFF_ENABLED",
    "EDGE_LIGHT_AOD_ENABLED",
    "EDGE_LIGHT_AURORA_COLOR_MODE",
):
    add(
        key in settings,
        f"Framework Settings declares {key}",
        "Settings.java",
    )

for token in (
    "val topEnabled: Boolean",
    "val sidesEnabled: Boolean",
    "val bottomEnabled: Boolean",
    "val screenOnEnabled: Boolean",
    "val screenOffEnabled: Boolean",
    "val aodEnabled: Boolean",
    "val auroraColorMode: String",
    "Settings.System.EDGE_LIGHT_AURORA_COLOR_MODE",
):
    add(
        token in edge_repo,
        f"Edge light repository contains {token}",
        "EdgeLightSettingsRepository.kt",
    )

for token in (
    "var showTop: Boolean",
    "var showSides: Boolean",
    "var showBottom: Boolean",
    "clipToSelectedRegions(canvas)",
    'const val EFFECT_AURORA = "aurora"',
    "AURORA_SEGMENTS = 56",
    "drawAuroraVertical(",
    "drawAuroraHorizontal(",
):
    add(
        token in edge_view,
        f"Edge light renderer contains {token}",
        "EdgeLightView.kt",
    )

for token in (
    "Display.STATE_OFF",
    "Display.STATE_DOZE",
    "TriggerState.SCREEN_ON",
    "TriggerState.SCREEN_OFF",
    "TriggerState.AOD",
    "listener.addNotificationHandler(this)",
    "settingsRepo.settingsFlow.collectLatest",
    "currentSettings.screenOnEnabled",
    "currentSettings.screenOffEnabled",
    "currentSettings.aodEnabled",
    'currentSettings.animationEffect == EFFECT_AURORA',
):
    add(
        token in edge_controller,
        f"Edge light controller contains {token}",
        "EdgeLightViewController.kt",
    )

# Ignore secure window flags: keep the preference wired through the client Window path and the
# server-side secure SurfaceControl path so screenshots and MediaProjection recordings both see
# the same behavior, including for an already-visible window when the toggle changes.
add(
    'public static final String WINDOW_IGNORE_SECURE = "window_ignore_secure";' in settings,
    "Global setting key for window_ignore_secure is declared",
    "Settings.java",
)

try:
    dispatch_attrs = braced_block(window, "protected void dispatchWindowAttributesChanged(")
    add(
        "Settings.Global.WINDOW_IGNORE_SECURE" in dispatch_attrs
        and "attrs.flags &= ~FLAG_SECURE;" in dispatch_attrs,
        "Window strips FLAG_SECURE whenever attributes are dispatched while override is enabled",
        "Window.java",
    )
except ValueError as exc:
    add(False, f"Unable to parse Window secure override: {exc}", "Window.java")

try:
    get_disable_secure = braced_block(wms, "boolean getDisableSecureWindows()")
    add(
        "Settings.Global.WINDOW_IGNORE_SECURE" in get_disable_secure
        and "mDisableSecureWindows" in get_disable_secure,
        "WindowManager server honors window_ignore_secure in secure-surface decisions",
        "WindowManagerService.java",
    )
except ValueError as exc:
    add(False, f"Unable to parse WMS secure override: {exc}", "WindowManagerService.java")

add(
    "mWindowIgnoreSecureUri" in wms
    and "Settings.Global.getUriFor(Settings.Global.WINDOW_IGNORE_SECURE)" in wms
    and "mRoot.refreshSecureSurfaceState();" in wms,
    "WMS observes window_ignore_secure and refreshes live secure surfaces",
    "WindowManagerService.java",
)

try:
    is_secure = braced_block(window_state, "boolean isSecureLocked()")
    add(
        "mWmService.getDisableSecureWindows()" in is_secure
        and "WindowManager.LayoutParams.FLAG_SECURE" in is_secure,
        "WindowState routes FLAG_SECURE through the override before marking a surface secure",
        "WindowState.java",
    )
except ValueError as exc:
    add(False, f"Unable to parse WindowState secure decision: {exc}", "WindowState.java")

try:
    refresh_secure = braced_block(root_window, "void refreshSecureSurfaceState()")
    add(
        "w.setSecureLocked(w.isSecureLocked())" in refresh_secure,
        "Live windows re-apply their secure SurfaceControl state after toggle changes",
        "RootWindowContainer.java",
    )
except ValueError as exc:
    add(False, f"Unable to parse secure-surface refresh: {exc}", "RootWindowContainer.java")

add(
    "testIsSecureLocked_windowIgnoreSecure" in window_state_tests
    and "Settings.Global.WINDOW_IGNORE_SECURE" in window_state_tests
    and "assertFalse(window.isSecureLocked())" in window_state_tests,
    "WM test covers enabling and disabling window_ignore_secure on the same window",
    "WindowStateTests.java",
)

# QR RTL override: keep the framework exception pinned to the actual Evolution-X Google QR proxy
# rather than every activity hosted by Google Play services.
add(
    f'"{GOOGLE_QR_ACTIVITY}"' in activity,
    "Framework declares the exact Google QR scanner activity",
    "ActivityThread.java",
)
add(
    f'"{GOOGLE_QR_ACTIVITY}"' in qr,
    "SystemUI declares the exact Google QR scanner activity",
    "QRCodeScannerController.java",
)

try:
    qr_override = braced_block(activity, "private static void applyQrScannerLayoutDirectionOverride(")
    add(
        "GOOGLE_PLAY_SERVICES_PACKAGE.equals(r.activityInfo.packageName)" in qr_override
        and "GOOGLE_QR_SCANNER_ACTIVITY.equals(r.activityInfo.name)" in qr_override
        and "getBooleanExtra(EXTRA_FORCE_LTR_LAYOUT_DIRECTION, false)" in qr_override,
        "Framework QR override requires package, exact activity, and explicit marker",
        "ActivityThread.java",
    )
except ValueError as exc:
    add(False, f"Unable to parse QR override helper: {exc}", "ActivityThread.java")

try:
    update_qr = braced_block(qr, "private void updateQRCodeScannerActivityDetails()")
    add(
        "GOOGLE_PLAY_SERVICES_PACKAGE.equals(componentName.getPackageName())" in update_qr
        and "GOOGLE_QR_SCANNER_ACTIVITY.equals(componentName.getClassName())" in update_qr
        and "intent.putExtra(EXTRA_FORCE_LTR_LAYOUT_DIRECTION, true)" in update_qr,
        "SystemUI only marks the exact Google QR scanner proxy",
        "QRCodeScannerController.java",
    )
except ValueError as exc:
    add(False, f"Unable to parse QR scanner setup: {exc}", "QRCodeScannerController.java")

add(
    activity.count("applyQrScannerLayoutDirectionOverride(r);") == 2,
    "QR layout override is applied at launch and after configuration changes",
    "ActivityThread.java",
)

# Dynamic Bar clipboard: a cleared clipboard must invalidate stale asynchronous work, and event
# publication must be atomic with generation checks.
try:
    empty_clip = braced_block(island, "if (!clipboardManager.hasPrimaryClip())")
    add(
        "cleanupActiveClipboardLeases(state)" in empty_clip
        and "lastClipboardToken = null" in empty_clip
        and "invalidatePendingClipboardWorkAndPersist(state)" in empty_clip
        and "_clipboardEvent.value = null" in empty_clip,
        "Empty clipboard clears leases, token, pending work, and visible event",
        "SystemIslandManager.kt",
    )
except ValueError as exc:
    add(False, f"Unable to parse empty-clipboard branch: {exc}", "SystemIslandManager.kt")

try:
    commit_method = braced_block(island, "private fun commitClipboardEvent(")
    sync_start, sync_end = braced_range(commit_method, "synchronized(clipboardHistory)")
    sync_block = commit_method[sync_start : sync_end + 1]
    add(
        commit_method.count("_clipboardEvent.value = event") == 1
        and "_clipboardEvent.value = event" in sync_block,
        "Clipboard event publication is atomic with user/generation validation",
        "SystemIslandManager.kt",
    )
except ValueError as exc:
    add(False, f"Unable to parse clipboard commit path: {exc}", "SystemIslandManager.kt")

failed = [item for item in checks if not item[0]]
for ok, description, file_name in checks:
    print(f"[{'OK' if ok else 'FAIL'}] {description} :: {file_name}")

if failed:
    print(f"\n{len(failed)} regression check(s) failed.", file=sys.stderr)
    sys.exit(1)

print(f"\nAll {len(checks)} framework regression checks passed.")
