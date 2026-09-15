# Releases and repository administration

## Automatic releases

The single `CI and release` workflow runs on pull requests and pushes to `main`.

1. Pull requests run repository hygiene/Gitleaks, backend tests, and Android compilation/tests/lint using synthetic Firebase configuration.
2. Protected `main` requires these checks, an up-to-date branch, and a pull request. Force pushes and deletion are disabled; administrators follow the same rules.
3. A successful merge runs the checks again and builds the signed release APK using repository secrets.
4. The workflow publishes a GitHub Release containing only the APK and `SHA256SUMS`.

Backend deployments are manual and are not triggered by an Android release. No backend service-account key belongs in GitHub Actions.

### Versioning and retries

`VERSION` contains the major/minor prefix. The patch component is the count of first-parent commits at the merged `main` commit. Android's version code is `1000 + that count`.

This gives every main merge a unique version without a version-bump bot. Re-running the same commit uses the same tag and does not publish a duplicate. Do not rewrite `main` history. Example: prefix `0.4` and main commit count `2` produces tag `v0.4.2` and Android version code `1002`.

A failed build does not publish a release. Fix it through another PR or rerun the failed workflow if the failure was transient. Draft releases can be completed safely by a rerun. Builds are not cancelled when another merge arrives; each eligible merge gets its own release. An older build finishing late does not replace the release for the current main tip as latest.

## Required GitHub Actions secrets

| Name | Value |
| --- | --- |
| `FIREBASE_CONFIG_BASE64` | Base64 of production Android `google-services.json` |
| `BACKEND_URL` | HTTPS origin for the release API |
| `RELEASE_KEYSTORE_BASE64` | Base64 of the dedicated release keystore |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Signing alias |
| `RELEASE_KEY_PASSWORD` | Signing key password |

Only the main-branch release job reads these secrets. Its temporary signing files are removed after use. No APK/configuration from an untrusted pull request is promoted to a release.

## Initial signing setup

```bash
python3 scripts/configure_release.py init
python3 scripts/configure_release.py fingerprints
```

This generates `.private/release.keystore` and `.private/release-signing.json`, both excluded from Git. **Back up both files securely.** Losing the release key prevents ordinary APK updates. Do not regenerate it for each release.

Register the new SHA-1/SHA-256 with your Android app in Firebase and refresh `app/google-services.json` before uploading CI secrets. Then:

The public certificate's lowercase SHA-256 digest must match `signing-certificate.sha256`. This fingerprint is public verification metadata, not a private key. A fork using its own release key must update it.

```bash
python3 scripts/configure_release.py github OWNER/REPOSITORY
```

The helper uses `gh secret set` via standard input and does not print secret values. It never overwrites an existing local signing identity.

For a local signing check:

```bash
python3 scripts/configure_release.py build --version 0.4.0 --code 1000
```

Routine official releases should use the automated workflow so versions stay monotonic. Public release builds are non-debuggable and use the same dedicated key. The original development APK needs a one-time reinstall to switch signing identities; normal release updates preserve app data.

## Repository settings

- Default branch: `main`.
- Squash merges only; delete merged branches.
- Required checks: `Repository hygiene`, `Backend checks`, `Android checks`.
- Require pull requests and resolved review conversations; require the branch to be up to date.
- No mandatory second approval for a single maintainer; review contributions before merging.
- Enforce protection for administrators; no force pushes or branch deletion.
- Actions default to read-only; the release job alone requests contents write access.
- Private vulnerability reporting and available secret-scanning protections should be enabled.

The one-time repository bootstrap creates `main` before protection can be attached. All subsequent application changes go through a checked pull request.
