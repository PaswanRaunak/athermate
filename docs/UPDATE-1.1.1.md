# ScootScribe 1.1.1

This update fixes charge-limit enforcement and simplifies the dashboard and widget.
Package `io.ather.pro`, version code **3**, version name **1.1.1**.

## Install over the existing app

Open `ScootScribe-v1.1.1-update.apk` and choose **Update**. Do not uninstall or clear
the existing app. The application ID, signing certificate, encrypted session
preferences and database are unchanged. An existing valid session is preserved;
an expired server session can still require sign-in.

The local verifier can install safely when the phone is connected and authorized:

```sh
python3 scripts/verify-update.py --install
```

It verifies the installed package and certificate before using `adb install -r`.
Open the app after updating to resume charging monitoring and refresh the widget.

## Charge limit

The automatic limit and the Pause button both call the repository's `requestPause`
function, which sends the existing HTTP `action=stop` command. No new charger
command or endpoint is introduced.

- The trigger is **battery percentage >= applied limit**, including an overshoot
  or setting a limit below the current battery reading.
- A desired-shadow `stop` echo no longer fabricates a paused state, confirms the
  command, or shuts down monitoring. Confirmation requires a fresh physical
  charging/paused/disconnected reading received after the request.
- A fresh battery reading can use a recent charging-state reading from a separate
  packet. Battery freshness remains 30 seconds; active-charge evidence may be up
  to 2 minutes old. GPS packets and desired-command echoes refresh neither clock.
- The first retries are spaced by at least 60 seconds. After three attempts,
  retries slow to at most once every 5 minutes while fresh readings still show
  charging at/above the limit. A temporary outage no longer permanently exhausts
  the automatic limit. One request remains pending at a time.
- Late physical stop confirmations are accepted after a timeout. Fresh charging
  updates clear an old disconnected flag, keeping the next session controllable.
- The charging foreground service holds a timed, renewable partial wake lock
  while the automatic limit is active. It releases the lock when the service
  stops or the limit is disabled. Passive idle checks do not hold this lock.

The notification stays silent and is used during charging/stop confirmation.
Android schedules idle checks, which can be delayed; opening the app when plugging
in starts monitoring immediately. An offline or force-stopped phone cannot enforce
the limit. The cap is phone automation and cannot guarantee an exact hardware cutoff.
Wake-lock lifecycle follows [Android's wake-lock guidance](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock).

## Remaining range and simpler screens

The mode card and widget say **Range at 79% battery**, for example, and use the same
calculation. When the current mode is SmartEco and the main live range is 101 km,
the SmartEco tile is also **101 km**. Other mode estimates use the API's relative
mode ranges, anchored to that live value. The app does not multiply a remaining
range by battery percentage again. Missing mode estimates are not filled with
advertised full-charge numbers; the known current mode remains visible when its
live range is available.

Mode filtering follows the selected/detected model and the modes actually returned
by the API. The 450X keeps Warp, Apex uses Warp+, 450S excludes both, and Rizta
supports SmartEco/Eco/Zip. Warp+ aliases remain distinct from Warp. Capability
references: [Ather 450 specifications](https://www.atherenergy.com/450/specifications)
and [Rizta specifications](https://www.atherenergy.com/rizta/specifications).

Home contains battery, charging/map shortcuts, mode ranges, and the 100-to-0 battery
history graph. The ride graphs are removed. Battery health, odometer, savings and
trip history sit under the expandable vehicle details.

The minimal widget has a large battery percentage, the current range, supported
mode tiles, and a sync time. It has no graphs or decorative ring. It adapts to
light/dark mode and Android wallpaper colors; smaller sizes use compact mode rows.
Resize very small widgets to show every mode. Saved readings remain labeled with
their sync time, and fresh GPS packets cannot make an old battery reading appear live.

## Validation

- **121 unit tests passed**, including sparse charging packets, overshoot,
  desired-stop echoes, retry cooldown/backoff, delayed confirmation, model filtering,
  and the 79% / 101 km example in both dashboard logic and widget snapshot.
- Debug lint and release compilation passed. Lint has no errors.
- Package ID and certificate match the supplied original and the delivered 1.1.0 APK.
- No real charging commands were sent by the tests. The phone disconnected from ADB
  before final validation, so the new widget's launcher rendering and actual scooter
  cutoff still need a connected-device check.

The unchanged map and database migration retain their 1.1.0 validation results;
see [the earlier update notes](UPDATE-1.1.0.md).
