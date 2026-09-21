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

session_checks = [
    ("WallpaperManager.COMMAND_TAP.equals(action)", "Only semantic wallpaper taps are considered"),
    ("Settings.Secure.HOME_DOUBLE_TAP_TO_SLEEP", "Session reads the dedicated Home setting"),
    ("UserHandle.getUserId(mUid)", "Setting is resolved for the calling user's profile"),
    ("windowState == wallpaperController.getWallpaperTarget()", "Source must be the active wallpaper target"),
    ("windowState.getActivityType() != ACTIVITY_TYPE_HOME", "Source activity must be HOME"),
    ("windowState.getDisplayId() != DEFAULT_DISPLAY", "Gesture is limited to the default display"),
    ("!mService.mPowerManager.isInteractive()", "Device must be interactive"),
    (
        "mService.mAtmService.mKeyguardController.isKeyguardShowing(DEFAULT_DISPLAY)",
        "Gesture is blocked while keyguard is showing",
    ),
    ("ViewConfiguration.getDoubleTapTimeout()", "Android double-tap timeout is reused"),
    ("ViewConfiguration.getDoubleTapMinTime()", "Android double-tap minimum time is reused"),
    ("getScaledDoubleTapSlop()", "Android density-aware double-tap slop is reused"),
    (
        "wallpaperController.sendWindowWallpaperCommandUnchecked(",
        "Original wallpaper command is still forwarded",
    ),
    (
        "mService.mPowerManager.goToSleep(SystemClock.uptimeMillis())",
        "Sleep is performed through the native PowerManager path",
    ),
]
for needle, description in session_checks:
    require("Session.java", needle, description)

session = texts["Session.java"]
checks.append(
    (
        session.index("if (shouldSleep)") > session.index("synchronized (mService.mGlobalLock)"),
        "PowerManager sleep path is executed after the WindowManager global-lock section",
        "Session.java",
    )
)
checks.append(
    (
        "InputMonitor" not in session
        and "monitorGestureInput" not in session
        and "AccessibilityService" not in session,
        "Implementation does not add raw-input or Accessibility interception",
        "Session.java",
    )
)
checks.append(
    (
        session.count("{") == session.count("}"),
        "Session.java braces are balanced",
        "Session.java",
    )
)

failed = [item for item in checks if not item[0]]
for ok, description, file_name in checks:
    print(f"[{'OK' if ok else 'FAIL'}] {description} :: {file_name}")

if failed:
    print(f"\n{len(failed)} validation check(s) failed.", file=sys.stderr)
    sys.exit(1)

print(f"\nAll {len(checks)} framework Home DT2S release checks passed.")
