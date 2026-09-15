"""Run a checksum-pinned Gitleaks release against all reachable Git history."""
import hashlib
import io
import os
import platform
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

VERSION = "8.30.1"
ARCHIVES = {
    ("Linux", "x86_64"): ("linux_x64", "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"),
    ("Darwin", "arm64"): ("darwin_arm64", "b40ab0ae55c505963e365f271a8d3846efbc170aa17f2607f13df610a9aeb6a5"),
}

target, checksum = ARCHIVES[(platform.system(), platform.machine())]
url = f"https://github.com/gitleaks/gitleaks/releases/download/v{VERSION}/gitleaks_{VERSION}_{target}.tar.gz"
with urllib.request.urlopen(url, timeout=60) as response:
    archive = response.read()
if hashlib.sha256(archive).hexdigest() != checksum:
    raise RuntimeError("Gitleaks checksum mismatch")
with tempfile.TemporaryDirectory(prefix="bitcoin-secret-scan-") as directory:
    executable = Path(directory) / "gitleaks"
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
        member = tar.getmember("gitleaks")
        if not member.isfile():
            raise RuntimeError("Invalid Gitleaks archive")
        executable.write_bytes(tar.extractfile(member).read())
    executable.chmod(0o700)
    if "--staged" in sys.argv:
        diff = subprocess.check_output(["git", "diff", "--cached", "--no-ext-diff"])
        result = subprocess.run([str(executable), "stdin", "--redact", "--no-banner"], input=diff, env=os.environ)
    else:
        result = subprocess.run([str(executable), "git", ".", "--redact", "--no-banner", "--log-opts=--all"], env=os.environ)
    raise SystemExit(result.returncode)
