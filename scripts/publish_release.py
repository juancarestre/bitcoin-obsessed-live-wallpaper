"""Publish once per main commit. Safe to rerun after a partially completed release."""
import json
import os
import subprocess
from pathlib import Path

tag = os.environ["TAG"]
sha = os.environ["GITHUB_SHA"]
repo = os.environ["GH_REPO"]
code = os.environ["CODE"]


def gh(*args, check=True):
    return subprocess.run(["gh", *args], check=check, text=True, capture_output=True)


existing = gh("release", "view", tag, "--json", "isDraft,targetCommitish,url", check=False)
if existing.returncode == 0:
    release = json.loads(existing.stdout)
    if release["targetCommitish"] != sha:
        raise RuntimeError("Release tag points to an unexpected commit")
    if not release["isDraft"]:
        print(f"Release already published: {release['url']}")
        raise SystemExit(0)
else:
    notes = Path(os.environ.get("RUNNER_TEMP", ".")) / "release-notes.md"
    notes.write_text(f"""## Bitcoin Obsessed {tag}

Free Android live wallpaper, hourly candlestick widget, and Google-connected RSI/price alerts.

### Install
Download `bitcoin-obsessed-{tag}.apk` below and open it on Android 9 or newer.
Allow installation from your browser/file manager when Android asks.

- Free, with no ads or subscriptions.
- Signed with the project's dedicated release key. Future release APKs update in place.
- **Coming from a development/debug APK?** It uses a different key. Uninstall that build once before installing this release; this removes its local settings and wallpaper photo copy. Keep your original photo and note your alert settings first. Sign in again and configure your alerts after installing.
- Verify the download using the attached `SHA256SUMS` file.

Android version code: `{code}`. Source commit: `{sha}`.

See [installation and upgrades](https://github.com/{repo}/blob/main/docs/INSTALLING.md) for details.
""", encoding="utf8")
    gh("release", "create", tag, "--draft", "--target", sha, "--title", f"Bitcoin Obsessed {tag}", "--notes-file", str(notes))
files = sorted(str(path) for path in Path("dist").iterdir() if path.suffix == ".apk" or path.name == "SHA256SUMS")
if len(files) != 2:
    raise RuntimeError("Expected exactly one APK and one checksum file")
gh("release", "upload", tag, *files, "--clobber")
# An older build finishing late must not replace the latest main release.
head = gh("api", f"repos/{repo}/git/ref/heads/main", "--jq", ".object.sha").stdout.strip()
gh("release", "edit", tag, "--draft=false", f"--latest={'true' if head == sha else 'false'}")
print(gh("release", "view", tag, "--json", "url", "--jq", ".url").stdout.strip())
