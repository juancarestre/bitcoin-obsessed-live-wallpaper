"""Create a dedicated signing key, build locally, or upload release secrets with gh.

Secrets remain in ignored .private/ files and process environments, never CLI values/logs.
"""
import argparse
import base64
import json
import os
import secrets
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PRIVATE = ROOT / ".private"
CONFIG = PRIVATE / "release-signing.json"
KEYSTORE = PRIVATE / "release.keystore"


def run(command, **kwargs):
    subprocess.run(command, check=True, cwd=ROOT, **kwargs)


def initialize():
    PRIVATE.mkdir(mode=0o700, exist_ok=True)
    PRIVATE.chmod(0o700)
    if CONFIG.exists() and KEYSTORE.exists():
        print("Existing signing key preserved.")
        return
    if CONFIG.exists() or KEYSTORE.exists():
        raise RuntimeError("Partial signing setup exists. Inspect it manually; it will not be overwritten.")
    password = secrets.token_urlsafe(48)
    env = {**os.environ, "SIGNING_PASSWORD": password}
    run(["keytool", "-genkeypair", "-noprompt", "-alias", "release", "-keyalg", "RSA", "-keysize", "4096",
         "-validity", "10000", "-storetype", "PKCS12", "-keystore", str(KEYSTORE),
         "-storepass:env", "SIGNING_PASSWORD", "-keypass:env", "SIGNING_PASSWORD",
         "-dname", "CN=Bitcoin Obsessed, O=Bitcoin Obsessed"], env=env)
    KEYSTORE.chmod(0o600)
    fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as output:
        json.dump({"alias": "release", "store_password": password, "key_password": password}, output)
    print("Dedicated signing key created in .private/. Back up both files securely.")


def environment():
    config = json.loads(CONFIG.read_text())
    return {**os.environ, "RELEASE_KEYSTORE_FILE": str(KEYSTORE),
            "RELEASE_KEYSTORE_PASSWORD": config["store_password"],
            "RELEASE_KEY_ALIAS": config["alias"], "RELEASE_KEY_PASSWORD": config["key_password"]}


def upload(repo):
    env = environment()
    config = ROOT / "app/google-services.json"
    if not config.exists():
        raise RuntimeError("Configure app/google-services.json first")
    local_path = ROOT / "local.properties"
    local = dict(line.split("=", 1) for line in local_path.read_text().splitlines() if "=" in line and not line.startswith("#")) if local_path.exists() else {}
    backend_url = os.environ.get("BACKEND_URL") or local.get("backend.url")
    if not backend_url:
        raise RuntimeError("Set BACKEND_URL or backend.url in local.properties")
    values = {"RELEASE_KEYSTORE_BASE64": base64.b64encode(KEYSTORE.read_bytes()).decode(),
              "FIREBASE_CONFIG_BASE64": base64.b64encode(config.read_bytes()).decode(), "BACKEND_URL": backend_url,
              **{key: env[key] for key in ("RELEASE_KEYSTORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")}}
    for name, value in values.items():
        run(["gh", "secret", "set", name, "--repo", repo], input=value, text=True)
    print("Release secrets configured. No backend service-account credentials were uploaded.")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init")
    sub.add_parser("fingerprints")
    build = sub.add_parser("build")
    build.add_argument("--version", required=True)
    build.add_argument("--code", required=True)
    github = sub.add_parser("github")
    github.add_argument("repo")
    args = parser.parse_args()
    if args.command == "init":
        initialize()
    elif args.command == "github":
        upload(args.repo)
    elif args.command == "fingerprints":
        run(["keytool", "-list", "-v", "-keystore", str(KEYSTORE), "-alias", "release",
             "-storepass:env", "RELEASE_KEYSTORE_PASSWORD"], env=environment())
    elif args.command == "build":
        run([str(ROOT / "gradlew"), "assembleRelease"], env={**environment(), "RELEASE_VERSION_NAME": args.version, "RELEASE_VERSION_CODE": args.code})


if __name__ == "__main__":
    main()
