# Athr+ 1.1.0

Athr+ is an independent companion app for electric two-wheelers.
The Android application ID remains `io.ather.pro` so it can replace existing installations
while retaining encrypted login, trip records and preferences.

## What changed

- Home, Charging, Map and Settings navigation, with a concise battery/range overview,
  data freshness, available ride modes, trips, odometer and fuel savings.
- Battery history always spans **0–100%**, with labelled ticks and the selected
  charge limit. Touch inspection freezes the values until returning to live.
- Speed, odometer-based distance and range history have selectable time windows
  and touch inspection. Missing values stay missing, and long gaps are not joined.
- Charge targets use an explicit Apply action. A fresh reading **at or above** the
  target requests Stop/Pause.
- Charging estimates show remaining battery percentage, energy, cost, approximate
  target range and ETA when reference ETA is supplied.
- A larger street map has vehicle following, phone following, north/heading modes,
  visible missing-location state and location receipt time.
- A resizable 4×4 widget shows battery percentage, available range, current-charge
  range by mode, a 0–100% battery graph, target status and the last sync time.
- Material You colors are enabled by default on Android 12+, following system
  light/dark mode. An original adaptive icon includes Android's monochrome layer.

## Validation

Run from the repository root:

```sh
./android/gradlew -p android :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
node scripts/test-map.mjs
python3 scripts/test-migration.py
```
