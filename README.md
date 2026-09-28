# Ather Pro

Recovered Android project for **Ather Pro** (`io.ather.pro`, version `1.0.0`).
The project was restored on 2026-09-28 using the installed app's APK and surviving
development snapshots and edits after the original Turbo RAM disk was lost.

The app includes OTP login and scooter selection, live telemetry, charging
controls and charge limits, persisted trip history, battery history, scooter and
charger maps, ride analytics, notifications, and a home-screen widget.

## Open and build

Open the **android/** directory in Android Studio.

Requirements: JDK 17, Android SDK Platform 34, and Android Build Tools 34.0.0.
Gradle 8.11.1 is provided through the wrapper. Set `ANDROID_HOME` to your SDK,
or set `sdk.dir` in an untracked `android/local.properties` file.

```sh
cd android
./gradlew :app:assembleDebug
```

APK output: `android/app/build/outputs/apk/debug/app-debug.apk`.

```sh
# Compile, run unit tests, and check Android lint.
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# From the repository root: exercise map gestures in a local headless browser.
# Requires Node.js 22+ and Chromium; set CHROMIUM if its path differs.
node scripts/test-map.mjs
```

The recovery build passed **78 unit tests**, Android lint with **0 errors and
8 warnings**, and **6 browser map checks**. See [recovery details](docs/RECOVERY.md)
for provenance and remaining verification limits.

## Project layout

```text
android/app/src/main/java/io/ather/pro/
  data/          API, authentication, Room storage, and repositories
  domain/        Models, analytics, battery and charging logic
  presentation/  ViewModels
  ui/            Compose screens and components
  service/       Charging monitors
  util/          Notifications and alerts
  widget/        Home-screen widget
android/app/src/main/assets/  Maps and bundled Leaflet files
android/app/src/test/         Recovered unit tests and fixtures
scripts/test-map.mjs          Local browser map tests
```

This directory is on persistent disk. Git tracks the source, build configuration,
assets, and tests. Local APK backups, recovery logs, account data, signing keys,
and build outputs are excluded. The initial recovery commit starts new history;
the lost repository's original Git history was not recovered.
