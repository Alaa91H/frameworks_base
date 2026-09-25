#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
TILE = ROOT / "packages/SystemUI/src/com/android/systemui/qs/tiles/PowerShareTile.java"
TEST = ROOT / (
    "packages/SystemUI/multivalentTests/src/com/android/systemui/qs/tiles/"
    "PowerShareTileTest.java"
)

tile = TILE.read_text(encoding="utf-8")
test = TEST.read_text(encoding="utf-8")

checks = [
    (
        "private IPowerShare mPowerShare;" in tile
        and "private final IPowerShare mPowerShare;" not in tile,
        "PowerShare service cache can be replaced after Binder death",
    ),
    (
        "binder != null && binder.isBinderAlive()" in tile,
        "PowerShare Binder liveness is checked before cached reuse",
    ),
    (
        "ServiceManager.getService(fqName)" in tile
        and "IPowerShare.Stub.asInterface(binder)" in tile,
        "PowerShare service can be re-resolved",
    ),
    (
        tile.count("clearPowerShare(powerShare);") >= 3,
        "Failed PowerShare calls invalidate the cached proxy",
    ),
    (
        "setUnavailableState(state, R.string.quick_settings_powershare_unavailable);"
        in tile
        and "updateNotification(false);" in tile,
        "Service loss clears stale PowerShare notification state",
    ),
    (
        "private void ensureNotification()" in tile,
        "PowerShare notification resources are initialized lazily",
    ),
    (
        "isPowerShareAlive_nullService_returnsFalse" in test
        and "isPowerShareAlive_liveBinder_returnsTrue" in test
        and "isPowerShareAlive_deadBinder_returnsFalse" in test
        and "isPowerShareAlive_nullBinder_returnsFalse" in test,
        "PowerShare Binder liveness has focused regression coverage",
    ),
]

failed = [description for ok, description in checks if not ok]
for ok, description in checks:
    print(f"[{'OK' if ok else 'FAIL'}] {description}")

if failed:
    print(f"\n{len(failed)} PowerShare validation check(s) failed.", file=sys.stderr)
    sys.exit(1)

print(f"\nAll {len(checks)} PowerShare validation checks passed.")
