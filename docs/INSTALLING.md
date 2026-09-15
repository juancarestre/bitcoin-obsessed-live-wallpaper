# Installing Bitcoin Obsessed

## First installation

Download the APK attached to the [latest GitHub Release](https://github.com/juancarestre/bitcoin-obsessed-live-wallpaper/releases/latest). GitHub's automatic source ZIP/tar files are not installable Android apps.

Open the APK on Android 9 or newer. Android may ask you to allow installation from the browser or file manager used to open it. Grant notification permission when enabling alerts.

No Google Play developer account or beta enrollment is needed for APK distribution. This is not a Google Play installation.

## Updates

Download a newer release APK and install it over the existing release. The package and signing key stay the same, and the version code increases with each merge to `main`. Your settings and photo copy should be preserved.

There is no automatic in-app updater yet. You can use GitHub's **Watch → Custom → Releases** option to follow updates.

### Switching from a development/debug APK

Development builds and public release builds have different signatures. Android will not install one over the other.

1. Keep your original wallpaper photo in your gallery and note your alert settings.
2. Sign out of the development app while online to unregister its alert device.
3. Uninstall the development build. This deletes its local settings and private photo copy.
4. Install the public release APK, choose your photo again, sign in, and save your alerts.

This is a one-time transition. Do not uninstall for normal release-to-release updates.

## Verify a download

Each release contains `SHA256SUMS`. In a directory containing the APK and checksum file:

```bash
sha256sum -c SHA256SUMS
# macOS:
shasum -a 256 -c SHA256SUMS
```

Checksums detect corruption or unexpected file changes. Download both files from the official repository's release, not an untrusted mirror.

The public release signing-certificate fingerprint is recorded in [`signing-certificate.sha256`](../signing-certificate.sha256). The release workflow checks that every APK uses this same identity and is not debuggable.

## Troubleshooting

- **App not installed:** check Android version, available storage, and whether a development build with a different signature is already installed.
- **No notifications:** allow notification permission, inspect the Smart alerts channel, and check Do Not Disturb settings.
- **Price looks old:** inspect its last-update time. Android can defer background work; reopen the app or use the widget refresh action.
- **Google login fails on a self-built APK:** register that build's signing certificate in your Firebase project. The public release configuration will not automatically authorize someone else's signing key.
