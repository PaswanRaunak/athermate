# ScootScribe 1.1.0

ScootScribe is the independently branded update to the recovered Ather Pro app.
The Android application ID remains `io.ather.pro` so it can replace the installed
app while retaining its encrypted login, trip records and settings.

## Install over the existing app

Open **ScootScribe-v1.1.0-update.apk** on the phone and choose **Update**.
Do not uninstall the old app or clear its storage. The launcher name and icon
change to ScootScribe after installation. The package and signing certificate
match the supplied 1.0.0 APK, and the version code increases from 1 to 2.
The encrypted preference file, keys and Android Keystore alias are unchanged.
An expired or server-revoked Ather session can still require another OTP.

From this checkout, with the SDK configured:

```sh
python3 scripts/verify-update.py
python3 scripts/verify-update.py --install
```

The install command verifies the actual installed APK's certificate and version,
then uses only `adb install -r`. It never uninstalls or clears app data.

## What changed

- Home, Charging, Map and Settings navigation, with a concise battery/range overview,
  data freshness, available ride modes, trips, odometer and fuel savings.
- Battery history always spans **0–100%**, with labelled ticks and the selected
  charge limit. Touch inspection freezes the values until returning to live.
- Speed, odometer-based distance and range history have selectable time windows
  and touch inspection. Missing values stay missing, and long gaps are not joined.
  The new ride-history table does not reinterpret old zero-speed placeholders.
- Charge targets use an explicit Apply action. A fresh reading **at or above** the
  target requests Stop/Pause, including a jump from below 70% to above 70%.
  Acknowledgement is distinct from scooter confirmation. Pending requests survive
  restart, with at most three automatic attempts per cycle and a 60-second retry
  cooldown; fresh evidence is required for every retry. Manual Start/Stop controls
  remain available, and stale confirmations no longer unlock them prematurely.
- Charging estimates show remaining battery percentage, energy, cost, approximate
  target range and ETA when the scooter supplies a reference ETA.
- A larger street map has scooter following, phone following, north/heading modes,
  visible missing-location state and location receipt time. The WebView and phone
  location/compass listeners stop when the map is not visible.
- A resizable 4×4 widget shows battery percentage, available range, current-charge
  range by mode, a 0–100% battery graph, target status and the last sync time.
  Existing small widget instances can be resized to reveal the graph and modes.
- Material You colors are enabled by default on Android 12+, following system
  light/dark mode. An original adaptive icon includes Android's monochrome layer
  for launchers with themed icons enabled.

## Background behavior

Notifications are silent while charging and disappear when charging stops.
Idle checks use WorkManager with a 15-minute interval and network availability;
Android can delay them. When charging is detected, foreground execution maintains
live telemetry and charge-limit checks. Opening the app when plugging in starts
monitoring immediately instead of waiting for a scheduled idle check.

Continuous background work requires an Android foreground notification. No attempt
is made to hide that notification or defeat Android's battery restrictions. A
force-stopped app cannot enforce a limit until reopened. Internet loss, delayed
scooter reports, server failures or Android stopping execution can delay a cutoff;
this remains phone automation, not a firmware-enforced battery cap. A brief status
notification may appear while a killed charging service restores its session.

See Android's [long-running worker documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
and [foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

## Battery health

The app accepts explicit battery SoH percentage fields when present in telemetry.
Their availability has not been verified against the connected scooter during this
update. General vehicle-health scores, odometer age and range are not labelled as
BMS SoH.

Without a reported percentage, the card estimates capacity from independent
**official ride energy/efficiency**, local odometer distance and **measured SoC
loss**, relative to the selected usable battery capacity. It needs at least three
reference rides covering 20 km and five local samples with at least 5% SoC use.
Until enough usable data exists, it shows collection progress instead of an invented
percentage. The result is explicitly an estimate affected by riding conditions,
temperature and model capacity, not a BMS diagnosis or a warranty determination.

## Architecture and compatibility

`AtherApplication` is the composition root. Application-level monitoring owns the
repository connection; disposing a dashboard ViewModel does not disconnect it.
The repository does not import Android services or UI classes. Domain policies for
charge evidence, thresholds, retries, monitoring, range and chart samples are pure
Kotlin. A serialized worker handles telemetry and persistence. Room no longer
permits main-thread queries or destructive migration. Database migration 1→2 adds
only the nullable ride-history table and preserves existing tables.

The new name, icon and independence notice avoid presenting the app as an official
Ather product. They are not a trademark clearance or a guarantee about legal rights.

## Validation

Run from the repository root:

```sh
./android/gradlew -p android :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
node scripts/test-map.mjs
python3 scripts/test-migration.py
python3 scripts/verify-update.py
```

Tests use fixtures and local browser tiles. They do not send live scooter commands.
Physical-device installation, OEM background behavior, remote command acceptance
and the final screen layouts still need device verification: no authorized phone
was available over ADB during the build.
