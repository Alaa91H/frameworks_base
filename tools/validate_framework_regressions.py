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

texts = {
    "ActivityThread.java": ACTIVITY_THREAD.read_text(encoding="utf-8"),
    "QRCodeScannerController.java": QR_CONTROLLER.read_text(encoding="utf-8"),
    "SystemIslandManager.kt": SYSTEM_ISLAND.read_text(encoding="utf-8"),
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
