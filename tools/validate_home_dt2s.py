#!/usr/bin/env python3
"""Static release checks for native Home-screen double-tap-to-sleep."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

SETTINGS = ROOT / "core/java/android/provider/Settings.java"
BACKUP = ROOT / "packages/SettingsProvider/src/android/provider/settings/backup/SecureSettings.java"
VALIDATORS = ROOT / "packages/SettingsProvider/src/android/provider/settings/validators/SecureSettingsValidators.java"
SESSION = ROOT / "services/core/java/com/android/server/wm/Session.java"

texts = {
    "Settings.java": SETTINGS.read_text(encoding="utf-8"),
    "SecureSettings.java": BACKUP.read_text(encoding="utf-8"),
    "SecureSettingsValidators.java": VALIDATORS.read_text(encoding="utf-8"),
    "Session.java": SESSION.read_text(encoding="utf-8"),
}

checks = []


def require(file_name: str, needle: str, description: str) -> None:
    checks.append((needle in texts[file_name], description, file_name))


def require_count(file_name: str, needle: str, expected: int, description: str) -> None:
    count = texts[file_name].count(needle)
    checks.append(
        (count == expected, f"{description} (found {count}, expected {expected})", file_name)
    )


def braced_range(text: str, anchor: str) -> tuple[int, int]:
    """Return the inclusive range of the first braced block following anchor."""
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


require_count(
    "Settings.java",
    'HOME_DOUBLE_TAP_TO_SLEEP = "home_double_tap_to_sleep"',
    1,
    "Dedicated Home secure-setting key is defined exactly once",
)
require(
    "SecureSettings.java",
    "Settings.Secure.HOME_DOUBLE_TAP_TO_SLEEP",
    "Home DT2S is included in secure-settings backup",
)
require(
    "SecureSettingsValidators.java",
    "Secure.HOME_DOUBLE_TAP_TO_SLEEP, BOOLEAN_VALIDATOR",
    "Home DT2S restore values are boolean-validated",
)

session = texts["Session.java"]
try:
    send_method = braced_block(
        session,
        "public void sendWallpaperCommand(IBinder window, String action, int x, int y,",
    )
    helper_method = braced_block(session, "private boolean shouldSleepOnWallpaperTap(")
    lock_start, lock_end = braced_range(
        send_method, "synchronized (mService.mGlobalLock)"
    )

    scoped_checks = [
        (
            "WallpaperManager.COMMAND_TAP.equals(action)" in send_method,
            "Only semantic wallpaper taps are considered",
        ),
        (
            "Settings.Secure.HOME_DOUBLE_TAP_TO_SLEEP" in send_method,
            "Session reads the dedicated Home setting",
        ),
        (
            "UserHandle.getUserId(mUid)" in send_method,
            "Setting is resolved for the calling user's profile",
        ),
        (
            "final boolean isActiveWallpaperTarget =" in send_method
            and "windowState == wallpaperController.getWallpaperTarget()" in send_method,
            "The active wallpaper target is resolved explicitly",
        ),
        (
            "if (mCanAlwaysUpdateWallpaper || isActiveWallpaperTarget)" in send_method,
            "Privileged wallpaper commands keep their existing forwarding behavior",
        ),
        (
            "if (isWallpaperTap && isActiveWallpaperTarget)" in send_method,
            "Home DT2S is gated to taps from the active wallpaper target",
        ),
        (
            "wallpaperController.sendWindowWallpaperCommandUnchecked(" in send_method,
            "Original wallpaper command is still forwarded",
        ),
        (
            "mService.mPowerManager.goToSleep(SystemClock.uptimeMillis())" in send_method,
            "Sleep is performed through the native PowerManager path",
        ),
        (
            send_method.count("mService.mPowerManager.goToSleep(") == 1,
            "Wallpaper command path has exactly one sleep call",
        ),
        (
            send_method.find("if (shouldSleep)") > lock_end,
            "PowerManager sleep path is executed after the WindowManager global-lock block",
        ),
        (
            "mService.mPowerManager.goToSleep(" not in send_method[lock_start : lock_end + 1],
            "No PowerManager sleep call occurs while holding the WindowManager global lock",
        ),
    ]
    for ok, description in scoped_checks:
        checks.append((ok, description, "Session.java"))

    helper_checks = [
        (
            "windowState.getDisplayId() != DEFAULT_DISPLAY" in helper_method,
            "Gesture is limited to the default display",
        ),
        (
            "windowState.getActivityType() != ACTIVITY_TYPE_HOME" in helper_method,
            "Source activity must be HOME",
        ),
        (
            "!mService.mPowerManager.isInteractive()" in helper_method,
            "Device must be interactive",
        ),
        (
            "mService.mAtmService.mKeyguardController.isKeyguardShowing(DEFAULT_DISPLAY)"
            in helper_method,
            "Gesture is blocked while keyguard is showing",
        ),
        (
            "elapsed >= DOUBLE_TAP_MIN_TIME_MS" in helper_method
            and "elapsed <= DOUBLE_TAP_TIMEOUT_MS" in helper_method,
            "Android double-tap timing bounds are enforced",
        ),
        (
            "deltaX * deltaX + deltaY * deltaY <= mDoubleTapSlopSquared"
            in helper_method,
            "Density-aware double-tap slop is enforced",
        ),
    ]
    for ok, description in helper_checks:
        checks.append((ok, description, "Session.java"))
except ValueError as exc:
    checks.append((False, f"Unable to parse DT2S method structure: {exc}", "Session.java"))

require(
    "Session.java",
    "ViewConfiguration.getDoubleTapTimeout()",
    "Android double-tap timeout is reused",
)
require(
    "Session.java",
    "ViewConfiguration.getDoubleTapMinTime()",
    "Android double-tap minimum time is reused",
)
require(
    "Session.java",
    "getScaledDoubleTapSlop()",
    "Android density-aware double-tap slop is reused",
)

failed = [item for item in checks if not item[0]]
for ok, description, file_name in checks:
    print(f"[{'OK' if ok else 'FAIL'}] {description} :: {file_name}")

if failed:
    print(f"\n{len(failed)} validation check(s) failed.", file=sys.stderr)
    sys.exit(1)

print(f"\nAll {len(checks)} framework Home DT2S release checks passed.")
