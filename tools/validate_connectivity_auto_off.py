#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = ROOT / "services/core/java/com/android/server/ConnectivityAutoOffController.java"

def fail(message: str) -> None:
    raise SystemExit(message)

def require(text: str, needle: str, where: str) -> None:
    if needle not in text:
        fail(f"{where}: missing invariant: {needle}")

def method_body(text: str, signature: str) -> str:
    start = text.find(signature)
    if start < 0:
        fail(f"missing method: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        fail(f"missing opening brace: {signature}")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
    fail(f"unterminated method: {signature}")

text = CONTROLLER.read_text(encoding="utf-8")

for token in (
    'WIFI_AUTO_OFF_TIMEOUT = "wifi_auto_off_timeout"',
    'BLUETOOTH_AUTO_OFF_TIMEOUT = "bluetooth_auto_off_timeout"',
    "TIMEOUT_MIN = 15_000L",
    "TIMEOUT_MAX = 8 * 60 * 60 * 1000L",
    "WifiManager.NETWORK_STATE_CHANGED_ACTION",
    "BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED",
    "isWifiConnectedOrConnecting()",
    "isBluetoothConnectedOrConnecting()",
    "mWifiAlarmAt",
    "mBluetoothAlarmAt",
    "mWifiAlarmTimeout",
    "mBluetoothAlarmTimeout",
):
    require(text, token, "ConnectivityAutoOffController")

for signature, at_field, timeout_field in (
    ("private void scheduleWifiAlarm()", "mWifiAlarmAt", "mWifiAlarmTimeout"),
    ("private void scheduleBluetoothAlarm()", "mBluetoothAlarmAt", "mBluetoothAlarmTimeout"),
):
    body = method_body(text, signature)
    require(body, f"{at_field} != 0 && {timeout_field} == timeout", signature)
    require(body, "setAndAllowWhileIdle(", signature)
    if "setExactAndAllowWhileIdle(" in body:
        fail(f"{signature}: exact alarm scheduling is not allowed")

for signature, guard, disable in (
    ("private void handleWifiAlarm()", "isWifiConnectedOrConnecting()", "setWifiEnabled(false)"),
    ("private void handleBluetoothAlarm()", "isBluetoothConnectedOrConnecting()", "mBluetoothAdapter.disable()"),
):
    body = method_body(text, signature)
    require(body, guard, signature)
    require(body, disable, signature)
    if body.find(guard) > body.find(disable):
        fail(f"{signature}: connection guard must run before radio disable")

wifi_update = method_body(text, "private void updateWifiAlarm()")
require(wifi_update, "!isWifiConnectedOrConnecting()", "updateWifiAlarm")

bt_update = method_body(text, "private void updateBluetoothAlarm()")
require(bt_update, "!isBluetoothConnectedOrConnecting()", "updateBluetoothAlarm")

get_timeout = method_body(text, "private long getTimeout(String key)")
for token in (
    "timeout == TIMEOUT_DISABLED",
    "timeout < TIMEOUT_MIN",
    "timeout > TIMEOUT_MAX",
    "Ignoring invalid auto-off timeout",
):
    require(get_timeout, token, "getTimeout")

for signature, at_field, timeout_field in (
    ("private void cancelWifiAlarm()", "mWifiAlarmAt", "mWifiAlarmTimeout"),
    ("private void cancelBluetoothAlarm()", "mBluetoothAlarmAt", "mBluetoothAlarmTimeout"),
):
    body = method_body(text, signature)
    require(body, f"{at_field} = 0", signature)
    require(body, f"{timeout_field} = 0", signature)

print("Connectivity Auto-off validation passed")
