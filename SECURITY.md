# Security

Report security vulnerabilities using this repository's **Security → Report a vulnerability** feature. Do not include secrets, authentication tokens, private photos, or other users' data in public issues.

## Credentials and configuration

- Android release signing keys and passwords are GitHub Actions secrets. Local signing files are kept outside Git with restrictive filesystem permissions and must be backed up securely by the maintainer.
- Production Firebase client configuration and the backend URL are injected into release builds. Client configuration is included in the APK by design; it is not a server credential and must not be used as an authorization mechanism.
- Backend service-account credentials live only in the deployment platform's secret store. GitHub release jobs do not receive them.
- The API verifies signed Google/Firebase identity tokens and checks device ownership. No client-controlled access flag grants authorization.
- Pull-request jobs use synthetic Firebase configuration and cannot access release secrets.
- Actions are pinned to commit SHAs; Gradle and secret-scanner downloads are checksum verified.

If a credential is exposed, revoke/rotate it at its issuer. Deleting a file or a commit is not sufficient. Signing-key rotation requires a separate Android upgrade-compatibility plan.
