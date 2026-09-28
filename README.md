# ScootScribe

Independent Android scooter companion, updated from the recovered **Ather Pro**
project (`io.ather.pro`, version **1.1.0**, version code **2**).

Install `ScootScribe-v1.1.0-update.apk` **over the existing app** to keep its saved
login. The signing certificate and encrypted session storage are unchanged.
See [what changed and how to update](docs/UPDATE-1.1.0.md).
The project was restored on 2026-09-28 using the installed app's APK and surviving
development snapshots and edits after the original Turbo RAM disk was lost.

The app includes live battery/range data, explicit charge limits with bounded stop
retries, battery/speed/distance/range graphs, qualified battery-health estimates,
a scooter map, silent charging monitoring, scheduled idle checks, and a graph-and-mode
home-screen widget. Material You colors and an adaptive themed icon are enabled.
Recovered charger-map and analytics modules also remain in the source.

## Open and build

Open the **android/** directory in Android Studio.

Requirements: JDK 17, Android SDK Platform 34, and Android Build Tools 34.0.0.
Gradle 8.11.1 is provided through the wrapper. Set `ANDROID_HOME` to your SDK,
or set `sdk.dir` in an untracked `android/local.properties` file.

```sh
cd android
./gradlew :app:assembleRelease
```

APK output: `android/app/build/outputs/apk/release/app-release.apk`.
The local update artifact is `ScootScribe-v1.1.0-update.apk`.

```sh
# Compile, run unit tests, and check Android lint.
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease

# From the repository root: exercise map gestures in a local headless browser.
# Requires Node.js 22+ and Chromium; set CHROMIUM if its path differs.
node scripts/test-map.mjs
python3 scripts/test-migration.py
python3 scripts/verify-update.py
```

The update passes **103 unit tests**, **9 browser map checks**, and the populated
SQLite migration check. Android lint has no errors. Package identity and signing
certificate are verified against the supplied original APK. Live scooter commands
and physical-device behavior were not exercised; the phone was unavailable to ADB.
See [recovery details](docs/RECOVERY.md) for original provenance.

## Project layout

```text
android/app/src/main/java/io/ather/pro/
  data/          API, authentication, Room storage, and repositories
  domain/        Models, analytics, battery and charging logic
  presentation/  ViewModels
  ui/            Compose screens and components
  service/       Charging foreground service, WorkManager checks, lifecycle policy
  util/          Notifications and alerts
  widget/        Home-screen widget
android/app/src/main/assets/  Maps and bundled Leaflet files
android/app/src/test/         Recovered unit tests and fixtures
scripts/                     Browser/SQLite tests and data-preserving update verification
```

This directory is on persistent disk. Git tracks the source, build configuration,
assets, and tests. Local APK backups, recovery logs, account data, signing keys,
and build outputs are excluded. The initial recovery commit starts new history;
the lost repository's original Git history was not recovered.
