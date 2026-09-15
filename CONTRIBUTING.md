# Contributing

Open an issue to discuss significant changes, then send a focused pull request. Keep app text, documentation, commit messages, and pull requests in English.

## Checks

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
npm --prefix backend ci
npm --prefix backend test
python3 scripts/check_repository.py
python3 scripts/scan_secrets.py
```

Repository checks operate on tracked/staged files. Stage intended changes before running the hygiene check. The secret scanner downloads a checksum-pinned Gitleaks binary and scans reachable Git history.

Use the example Firebase configuration for tests. Never submit real Firebase configuration, signing keys, service-account files, local deployment configuration, `.env` files, or personal screenshots.

## Pull requests

- Branch from `main`; direct pushes to `main` are blocked.
- Keep changes consistent with the existing native Android and backend structure.
- Include meaningful tests for authorization, alert behavior, and data migrations.
- Explain user-visible changes and how you tested them.
- The required checks are **Repository hygiene**, **Backend checks**, and **Android checks**.
- Squash merge after checks pass. A second reviewer is not mandatory for this single-maintainer project.
- A merge automatically creates a release after the main-branch checks and signing build succeed.

The initial pre-monorepo development builds used a different signing identity. Do not change the package name or release signing key as part of routine development.
