"""Verify the release package, version, non-debuggable flag, and public signing fingerprint."""
import argparse
import os
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("apk")
parser.add_argument("--version", required=True)
parser.add_argument("--code", required=True)
args = parser.parse_args()
local = Path("local.properties")
properties = dict(line.split("=", 1) for line in local.read_text().splitlines() if "=" in line) if local.exists() else {}
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or properties.get("sdk.dir")
if not sdk:
    raise RuntimeError("Set ANDROID_HOME")
tools = Path(sdk) / "build-tools/36.0.0"
signature = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", args.apk], text=True)
fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
expected = Path("signing-certificate.sha256").read_text().strip().lower()
if fingerprints != [expected]:
    raise RuntimeError("APK signing identity differs from the project's published certificate")
manifest = subprocess.check_output([str(tools / "aapt"), "dump", "badging", args.apk], text=True)
package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", manifest)
if not package or package.groups() != ("com.bitcoinobsessed.livewallpaper", args.code, args.version):
    raise RuntimeError("APK package or version does not match the release")
if "application-debuggable" in manifest:
    raise RuntimeError("Release APK must not be debuggable")
print(f"Verified {args.version}: signed release APK, expected package/version, debugging disabled.")
