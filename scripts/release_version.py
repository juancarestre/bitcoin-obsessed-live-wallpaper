"""Derive an immutable version from main's first-parent commit count."""
import os
import re
import subprocess
from pathlib import Path

prefix = Path("VERSION").read_text().strip()
if not re.fullmatch(r"\d+\.\d+", prefix):
    raise RuntimeError("VERSION must contain major.minor")
sha = os.environ.get("GITHUB_SHA", "HEAD")
count = int(subprocess.check_output(["git", "rev-list", "--first-parent", "--count", sha], text=True))
code = 1000 + count
if code > 2100000000:
    raise RuntimeError("Android version code exhausted")
version = f"{prefix}.{count}"
values = {"version": version, "code": str(code), "tag": f"v{version}"}
if path := os.environ.get("GITHUB_OUTPUT"):
    with open(path, "a") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")
print(f"Release {values['tag']} / Android versionCode {code}")
