# Potatoflix — Android and Android TV build

This is the only part of the repository: one APK serves phones/tablets (touch: bottom bar) and
Android TV / TV boxes (remote: icon rail, focus ring), with the same look and the same Kotlin code.

## Build

This is its own Gradle build.

```bash
../scripts/fetch-upstream.sh      # the pinned CloudStream library sources (shared with the library build)
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease         # app/build/outputs/apk/release/app-<abi>-release.apk
```

Needs JDK 17+ and the Android SDK (platform 36). `-PappVersion=7.0.0` sets the version (CI passes the release tag).

## Release signing

Android only installs an update over an existing app when both are signed with the same key, so releases must always
use one key. It lives in GitHub Actions secrets, never in the repository. Create it once (keep the file and passwords
safe: if the key is lost, users have to uninstall to move to a new one):

```bash
keytool -genkeypair -v -keystore potatoflix-release.jks -alias potatoflix -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 potatoflix-release.jks > keystore.b64
```

Then in the repository: Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the contents of `keystore.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `potatoflix` |
| `ANDROID_KEY_PASSWORD` | the key password (if different from the keystore password) |

Without the secrets, release builds are signed with the machine's debug key (fine for testing, but they can't update
or be updated by builds from other machines).

## CI and releases

`.github/workflows/android.yml` builds the APKs on every push to `main` and on pull requests (artifact
`potatoflix-android`). On `main` it also publishes a `v1.0.<run>` GitHub release with the APKs attached, named
`potatoflix-android-<abi>.apk`; `releases/latest` is always the newest build, and the app's update check picks it up.

APKs: `arm64-v8a` (most phones, newer TVs), `armeabi-v7a` (many Android TV boxes and Fire TV sticks),
`x86_64`/`x86` (emulators, a few Chromebooks) and `universal` (all of them, larger).

## Layout

- `cs-library/`: CloudStream's `library` module (the extension API) compiled for Android from `../upstream`,
  like the official app. YouTube is resolved with NewPipe Extractor.
- `app/src/main/java/com/lagradost/cloudstream3/`: the app-module classes extensions link against
  (`CloudStreamApp`, `DataStore`, `plugins.Plugin`, `CommonActivity`), with upstream's names and signatures.
- `app/src/main/java/io/github/sherazi5110/cloudstream/`: the app (Kotlin packages kept from upstream for
  compatibility; only what the user sees is Potatoflix). Extensions (`.cs3`) load natively with a
  `PathClassLoader`, as in the official app.
- `ui/`: the Jetpack Compose UI (Home, Search, title page, My list, Extensions, player, settings). `player/`:
  AndroidX Media3 (ExoPlayer) with this app's own controls (libVLC is gone).

Nothing is minified: extensions call into the library and its dependencies by name at runtime.
