#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CUSTOM = ROOT / "services/core/java/com/android/server/power/batterysaver/BatterySaverCustomActions.java"
CONTROLLER = ROOT / "services/core/java/com/android/server/power/batterysaver/BatterySaverController.java"
POLICY = ROOT / "services/core/java/com/android/server/power/batterysaver/BatterySaverPolicy.java"

def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path.read_text(encoding="utf-8")

def require(text: str, needle: str, where: str) -> None:
    if needle not in text:
        raise SystemExit(f"{where}: missing invariant: {needle}")

def method_body(text: str, signature: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"missing method: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"missing opening brace: {signature}")
    depth = 0
    for i in range(brace, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
    raise SystemExit(f"unterminated method: {signature}")

custom = read(CUSTOM)
controller = read(CONTROLLER)
policy = read(POLICY)

# Multi-user screen timeout invariants.
require(custom, 'SETTING_SCREEN_TIMEOUT_BACKUPS', "BatterySaverCustomActions")
require(custom, 'mPreviousScreenTimeouts', "BatterySaverCustomActions")
require(custom, 'UserHandle.USER_ALL', "screen timeout observer")
require(custom, 'Intent.ACTION_USER_SWITCHED', "user switch handling")
require(custom, 'loadScreenTimeoutBackups()', "screen timeout backup recovery")
require(custom, 'persistScreenTimeoutBackups()', "screen timeout persistence")
require(custom, 'handleScreenTimeoutChanged(ActivityManager.getCurrentUser())',
        "screen timeout fallback callback")
require(custom, 'Collection<android.net.Uri> uris', "screen timeout user-aware observer")
require(custom, 'UserHandle user', "screen timeout user-aware observer")
require(custom, 'handleScreenTimeoutChanged(user.getIdentifier())',
        "screen timeout user-aware dispatch")
require(custom, 'private void handleScreenTimeoutChanged(int changedUserId)',
        "screen timeout user-aware handler")
require(custom, 'changedUserId >= 0', "screen timeout user fallback")
require(custom, 'mPreviousScreenTimeouts.put(userId, currentTimeout)',
        "screen timeout per-user backup update")
require(custom, 'if (userId == ActivityManager.getCurrentUser())',
        "screen timeout foreground-only override")
require(custom, 'SubscriptionManager.OnSubscriptionsChangedListener', "5G subscription listener")
require(custom, 'addOnSubscriptionsChangedListener', "5G subscription listener registration")
require(custom, 'update5g(true);', "5G subscription policy re-apply")
require(custom, 'SETTING_5G_APPLIED_BACKUP', "5G ownership persistence")
require(custom, 'mAppliedPowerNetworkTypes', "5G ownership persistence")
require(custom, 'loadAppliedNetworkTypeBackups();', "5G ownership persistence")
require(custom, 'persistAppliedNetworkTypeBackups();', "5G ownership persistence")
require(custom, 'current != applied', "5G compare-before-restore")
require(custom, 'current == appliedMask', "5G ownership reload verification")
require(custom, 'withoutNr == current', "5G no-op ownership guard")

# CPU ownership / restore safety invariants.
require(custom, 'mAppliedCpuMaxFreqs', "CPU cap ownership")
require(custom, 'SETTING_CPU_APPLIED_FREQ_BACKUP', "CPU ownership persistence key")
require(custom, 'loadCpuAppliedFreqs();', "CPU ownership reload")
require(custom, 'persistCpuAppliedFreqs();', "CPU ownership persistence")
cpu_apply = method_body(custom, "private void updateCpuLimit(int requestedPercent)")
require(cpu_apply, 'target = Math.min(target, currentMax);', "CPU cap apply")
require(cpu_apply, 'currentMax != target', "CPU cap redundant-write guard")
require(cpu_apply, 'stillOwnsCurrentValue', "CPU cap ownership")

cpu_restore = method_body(custom, "private void restoreCpuMaxFreqs(boolean clearBackup)")
require(cpu_restore, 'currentMax != appliedMax', "CPU restore ownership check")
require(cpu_restore, 'Skipping CPU max restore', "CPU restore diagnostics")

# Full Battery Saver must snapshot before the Power HAL transition and apply once after it.
state_change = method_body(
    controller,
    "void handleBatterySaverStateChanged(boolean sendBroadcast, int reason)"
)
snapshot = state_change.find("mCustomActions.prepareForLowPowerTransition()")
power = state_change.find("pmi.setPowerMode(Mode.LOW_POWER")
apply = state_change.find("mCustomActions.setFullBatterySaverEnabled(fullEnabled)")
if min(snapshot, power, apply) < 0 or not (snapshot < power < apply):
    raise SystemExit(
        "BatterySaverController: CPU snapshot must precede LOW_POWER and custom apply must follow it"
    )
if state_change.count("mCustomActions.setFullBatterySaverEnabled(") != 1:
    raise SystemExit("BatterySaverController: custom actions must be applied exactly once per state change")
require(state_change, "fullEnabled && !fullPreviouslyEnabled", "Battery Saver transition snapshot guard")
require(state_change, "fullEnabled != fullPreviouslyEnabled", "Battery Saver transition apply guard")
require(state_change, "mCustomActions.reapplyCpuLimit()", "same-state CPU-only reapply")
require(custom, "private void snapshotCpuMaxFreqs()", "CPU pre-saver snapshot")
require(custom, "public void onChange(boolean selfChange, android.net.Uri uri)", "incremental setting observer")
require(custom, "updateCpuLimit(getCpuLimitOverride())", "incremental CPU setting update")
require(custom, "update5g(getDisable5gOverride())", "incremental 5G setting update")
require(custom, "updateScreenTimeout(getScreenTimeoutOverride())", "incremental timeout setting update")

# Policy-backed controls must remain observed and full-saver-only.
for key in (
    "SETTING_LOW_POWER_DISABLE_AOD",
    "SETTING_LOW_POWER_BRIGHTNESS_REDUCTION",
    "SETTING_LOW_POWER_FORCE_DARK",
):
    require(policy, key, "BatterySaverPolicy")
    require(policy, f"Settings.Global.getUriFor(\n                {key})", "BatterySaverPolicy observer")

require(policy, "if (mPolicyLevel == POLICY_LEVEL_FULL)", "BatterySaverPolicy")
require(policy, "brightnessReduction", "BatterySaverPolicy brightness override")

print("Battery Saver validation passed")
