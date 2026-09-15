"""Test the signed-in app's own push button on an authorized, unlocked debug device.

Only app UI/status is reported. Google/FCM tokens are never printed or exported.
Usage: python3 backend/smoke-google-device.py PIXEL_SERIAL
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APP = "com.bitcoinobsessed.livewallpaper"
SERIAL = sys.argv[1]


def adb(*args):
    return subprocess.check_output(["adb", "-s", SERIAL, *args], text=True, timeout=20)


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/bitcoin-ui-smoke.xml")
    root = ET.fromstring(adb("exec-out", "cat", "/sdcard/bitcoin-ui-smoke.xml"))
    if not any(n.get("package") == APP for n in root.iter("node")):
        raise RuntimeError("Unlock the phone and open Bitcoin Obsessed before running the test.")
    return root


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


started = int(time.time() * 1000)
for _ in range(10):
    root = ui()
    button = next((n for n in root.iter("node") if n.get("text") == "Send test notification"), None)
    if button is not None:
        tap(button)
        break
    adb("shell", "input", "swipe", "540", "2100", "540", "650", "450")
else:
    raise RuntimeError("Test button not found. Sign in with Google first.")

for _ in range(30):
    time.sleep(2)
    # Read only this debug app's private settings; do not inspect other apps' notifications.
    settings = ET.fromstring(adb("exec-out", "run-as", APP, "cat", "shared_prefs/settings.xml"))
    seen = settings.find("set[@name='seenAlerts']")
    if seen is not None and any(
        s.text and s.text.startswith("test:") and int(s.text.rsplit(":", 1)[-1]) >= started
        for s in seen.findall("string")
    ):
        print("PASS: Google-authenticated app → free alerts API → messaging → notification posted on device.")
        break
else:
    raise RuntimeError("No new test notification observed. Check notification permission and the app's message.")
