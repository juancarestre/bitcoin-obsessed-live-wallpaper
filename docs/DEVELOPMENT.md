# Development

## Android

Install JDK 17 and Android SDK platform/build-tools 36. Set `ANDROID_HOME` or create the ignored `local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
backend.url=https://your-api.example.com
```

For compilation and unit tests only:

```bash
cp app/google-services.example.json app/google-services.json
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For a working app, create a Firebase project, register the Android package `com.bitcoinobsessed.livewallpaper`, enable Google Authentication and Cloud Messaging, register your signing fingerprints, and download the real configuration to `app/google-services.json`.

Set the API origin through `backend.url` in `local.properties`, `BACKEND_URL` in your environment, or `-PbackendUrl=https://your-api.example.com`. Without configuration, debug builds use the nonfunctional `https://example.invalid` origin.

```bash
./gradlew signingReport
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APKs use a development key. See [INSTALLING.md](INSTALLING.md) before switching between debug and public release builds.

### Firebase configuration helper

With `gcloud` authenticated to your own project and a real `app/google-services.json` in place:

```bash
export ANDROID_SHA1='your-certificate-sha1'
export ANDROID_SHA256='your-certificate-sha256'
node backend/google-auth-setup.mjs --sha --download
```

The helper derives project/application identifiers from your ignored local configuration. Google Sign-In's server client ID must be the **web OAuth client**, not the Android client.

## Backend

```bash
npm --prefix backend ci
npm --prefix backend test
cp backend/wrangler.example.jsonc backend/wrangler.jsonc
```

Edit your local Wrangler configuration with your Firebase project and D1 database ID. Create a D1 database, apply migrations, configure the FCM service credential as a Worker secret, and deploy from `backend/`:

```bash
wrangler login
wrangler d1 create bitcoin-obsessed
wrangler d1 migrations apply bitcoin-obsessed --remote
wrangler secret put FCM_SERVICE_ACCOUNT
wrangler deploy
```

Use a Google service account with the Firebase Cloud Messaging API Admin role for sending pushes. `backend/provision-secrets.mjs` can create and upload its key when `FCM_SERVICE_ACCOUNT_EMAIL` and `FIREBASE_PROJECT_ID` are set. This helper creates a new key: run it only for deliberate provisioning/rotation, and retire obsolete keys.

The minute cron fetches shared market data, evaluates each linked device's rules, and sends owner-scoped notifications. Every verified Google account can use alerts. There is no premium allowlist or installation-code bypass.

### API

| Endpoint | Behavior |
| --- | --- |
| `GET /health` | Public service/version identification |
| `GET /account` | Verified identity and free alert access |
| `GET /device?id=...` | Read own device rules |
| `PUT /device` | Register/update own device and rules |
| `DELETE /device` | Unregister own device |
| `POST /test` | Send a test to own registered device |
| `GET /status` | Authenticated scheduler diagnostics, without user data |

Authenticated calls require a Firebase ID token with a verified email and Google sign-in provider. Tokens must match your configured project's issuer and audience.

## Tests and device helpers

Kotlin tests cover RSI calculations. Node tests use real SQLite and production migrations to cover multi-account isolation, JWT verification, RSI state transitions, price targets, deduplication, and rearming.

For an unlocked, signed-in **debug** device:

```bash
python3 backend/smoke-google-device.py DEVICE_SERIAL
python3 backend/smoke-free-upgrade.py DEVICE_SERIAL app/build/outputs/apk/debug/app-debug.apk
```

These helpers inspect only the debug app's own state and never print auth tokens. They cannot read private state from non-debuggable release APKs.
