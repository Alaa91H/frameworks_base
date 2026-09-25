#!/usr/bin/env python3
import argparse
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = ROOT / "services/core/java/com/android/server/ConnectivityAutoOffController.java"

parser = argparse.ArgumentParser()
parser.add_argument("--settings-root", type=Path)
args = parser.parse_args()

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


def validate_settings_contract(settings_root: Path) -> None:
    settings_root = settings_root.resolve()
    controller_path = (
        settings_root
        / "src/com/android/settings/network/ConnectivityAutoOffPreferenceController.java"
    )
    arrays_path = settings_root / "res/values/evolution_arrays.xml"
    wifi_xml_path = settings_root / "res/xml/network_provider_settings.xml"
    bluetooth_xml_path = settings_root / "res/xml/bluetooth_screen.xml"

    for path in (controller_path, arrays_path, wifi_xml_path, bluetooth_xml_path):
        if not path.is_file():
            fail(f"Settings contract file missing: {path}")

    settings_controller = controller_path.read_text(encoding="utf-8")
    for token in (
        'KEY_WIFI_AUTO_OFF_TIMEOUT = "wifi_auto_off_timeout"',
        'KEY_BLUETOOTH_AUTO_OFF_TIMEOUT = "bluetooth_auto_off_timeout"',
        "TIMEOUT_MIN = 15_000L",
        "TIMEOUT_MAX = 8 * 60 * 60 * 1000L",
    ):
        require(settings_controller, token, "Settings ConnectivityAutoOffPreferenceController")

    arrays_root = ET.parse(arrays_path).getroot()

    def array_items(name: str) -> list[str]:
        for child in arrays_root:
            if child.attrib.get("name") == name:
                return [(item.text or "").strip() for item in child if item.tag == "item"]
        fail(f"Settings arrays missing: {name}")

    entries = array_items("custom_timeout_entries")
    values = array_items("custom_timeout_values")
    expected_values = [
        "0", "15000", "30000", "60000", "120000", "300000",
        "600000", "1800000", "3600000", "7200000", "14400000", "28800000",
    ]
    if values != expected_values:
        fail(f"Settings custom_timeout_values mismatch: {values}")
    if len(entries) != len(values):
        fail(
            "Settings custom_timeout_entries/custom_timeout_values length mismatch: "
            f"{len(entries)} != {len(values)}"
        )

    android_ns = "http://schemas.android.com/apk/res/android"
    settings_ns = "http://schemas.android.com/apk/res-auto"
    android_key = f"{{{android_ns}}}key"
    android_entries = f"{{{android_ns}}}entries"
    android_values = f"{{{android_ns}}}entryValues"
    android_default = f"{{{android_ns}}}defaultValue"
    android_persistent = f"{{{android_ns}}}persistent"
    settings_controller_attr = f"{{{settings_ns}}}controller"

    expected_controller = (
        "com.android.settings.network.ConnectivityAutoOffPreferenceController"
    )

    def validate_preference(path: Path, expected_key: str) -> None:
        root = ET.parse(path).getroot()
        matches = [
            node for node in root.iter()
            if node.attrib.get(android_key) == expected_key
        ]
        if len(matches) != 1:
            fail(f"{path}: expected exactly one preference for {expected_key}")
        node = matches[0]
        expected_attrs = {
            android_entries: "@array/custom_timeout_entries",
            android_values: "@array/custom_timeout_values",
            android_default: "0",
            android_persistent: "false",
            settings_controller_attr: expected_controller,
        }
        for attr, expected in expected_attrs.items():
            actual = node.attrib.get(attr)
            if actual != expected:
                fail(
                    f"{path}: {expected_key} {attr} mismatch: "
                    f"{actual!r} != {expected!r}"
                )

    validate_preference(wifi_xml_path, "wifi_auto_off_timeout")
    validate_preference(bluetooth_xml_path, "bluetooth_auto_off_timeout")


if args.settings_root is not None:
    validate_settings_contract(args.settings_root)
    print("Settings/framework Connectivity Auto-off contract passed")
