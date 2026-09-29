# Athr+ 1.1.1

This update improves charge-limit enforcement and simplifies the dashboard and widget.
Package `io.ather.pro`, version code **3**, version name **1.1.1**.

## Charge limit

The automatic limit and the Pause button send an explicit request to pause charging.

- The trigger is **battery percentage >= applied limit**.
- Confirmation requires physical charging/paused/disconnected telemetry received after the request.
- The charging foreground service holds a timed, renewable partial wake lock while the automatic limit is active.
- Notifications stay silent during charging/stop confirmation.

## Remaining range and simpler screens

- Mode ranges display calculated values based on current battery charge.
- Supported mode filtering matches the detected model capabilities.
- Minimal home-screen widget with large battery readout, current range, and sync status.
