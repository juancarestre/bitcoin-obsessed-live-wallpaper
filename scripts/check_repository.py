"""Reject private files and common credential formats in the Git index."""
import fnmatch
import re
import subprocess
import sys

FORBIDDEN = (
    ".private/*", "screeshoots/*", "screenshots/*", "docs/archive/*",
    "docs/play-store-release.md", "*WhatsApp*", "*/google-services.json",
    "google-services.json", "*local.properties", "*signing.properties",
    "*.jks", "*.keystore", "*.p12", "*.pfx", "*.pem", "*.key",
    "*.apk", "*.aab", "*.log", "*.hprof", "*service-account*.json",
    "*service_account*.json", "*credentials*.json", "*.dev.vars*",
    "backend/wrangler.jsonc", "backend/wrangler.json", "backend/wrangler.toml",
    "*/node_modules/*", "*/build/*", ".gradle/*", ".kotlin/*",
)
PATTERNS = {
    "private key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "Google API key": re.compile(rb"AIza[0-9A-Za-z_-]{35}"),
    "GitHub token": re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})"),
    "AWS key": re.compile(rb"(?:AKIA|ASIA)[A-Z0-9]{16}"),
}


def main():
    paths = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
    failures = []
    for path in filter(None, paths):
        if any(fnmatch.fnmatch(path, pattern) for pattern in FORBIDDEN) or (
            any(part.startswith(".env") and part != ".env.example" for part in path.split("/"))
        ):
            failures.append(f"Private/generated file in Git: {path}")
            continue
        data = subprocess.check_output(["git", "show", f":{path}"])
        if b"\0" in data:
            continue  # Binary files are also checked by Gitleaks/history review.
        for label, pattern in PATTERNS.items():
            if pattern.search(data):
                failures.append(f"Possible {label}: {path} (value redacted)")
        for address in re.findall(rb"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", data):
            domain = address.decode().split("@")[-1].lower()
            if address == b"securetoken@system.gserviceaccount.com":
                continue  # Google's public JWT signing-key endpoint, not a project identity.
            if domain not in ("example.com", "example.org", "example.net") and not domain.endswith(".noreply.github.com"):
                failures.append(f"Non-example email in tracked content: {path} (value redacted)")
    if failures:
        print("\n".join(sorted(set(failures))), file=sys.stderr)
        return 1
    print("Repository hygiene passed: no private configuration, personal media, or matching credentials in the index.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
