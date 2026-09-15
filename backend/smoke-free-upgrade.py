"""Install the free build and verify that the debug app's existing alert settings survive.

Usage: python3 backend/smoke-free-upgrade.py SERIAL APK_PATH
Private settings are compared in memory only; no tokens or account IDs are printed.
"""
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

serial, apk = sys.argv[1:]
app = "com.bitcoinobsessed.livewallpaper"


def adb(*args):
    return subprocess.check_output(["adb", "-s", serial, *args], text=True, timeout=60)


def settings():
    root = ET.fromstring(adb("exec-out", "run-as", app, "cat", "shared_prefs/settings.xml"))
    return {n.get("name"): n.get("value", n.text) for n in root}


before = settings()
owner = before.get("accountUid") or before.get("premiumUid")
keys = ("deviceId", "alerts", "priceAlert", "period", "low", "high", "priceTarget", "priceRuleId")
adb("install", "-r", apk)
adb("shell", "am", "start", "-n", f"{app}/.MainActivity")
for attempt in range(20):
    time.sleep(1)
    after = settings()
    if "premiumUid" not in after and "premium" not in after:
        break
else:
    raise RuntimeError("Migration has not run. Open the updated app and try again.")
if owner and after.get("accountUid") != owner:
    raise RuntimeError("Existing account link was not preserved")
if any(before.get(key) != after.get(key) for key in keys):
    raise RuntimeError("Existing device or alert settings changed during upgrade")
print("PASS: free APK installed; account link, device ID and saved alert settings preserved.")
