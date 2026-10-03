# Energy artwork

The dashboard uses a model-independent battery visual drawn by `ui/visuals/BatteryArtwork.kt`. It replaces the illustrative 450X image. The scooter name still comes from the profile or selected model; the artwork works for any model without image mappings.

The battery fill and outer ring use the reported state of charge. A Material tertiary-color marker indicates the enabled limit, matching the limit label below the dashboard hero. Missing readings leave the battery empty without implying a measured 0%. The launcher icon uses the original scooter mark; the dashboard keeps the battery visual.

Charging adds a small energy flow and soft surface sheen; it never animates the reported percentage upwards. Motion runs only when the dashboard is visible, the activity is resumed, system animations are enabled, and the connected battery report is no more than two minutes old. Saved charging readings are labelled as saved and do not animate. Paused/stopped states remain still.

Widgets use the classic Material layout: an Athr+ header, battery percentage and current range above a single list of mode ranges, with no battery illustration. When the limiter is enabled, a slim bar shows the reported battery fill and a Material tertiary-color cutoff marker. It renders only when their existing snapshot updater runs, with no animation timer or extra API requests. This visual change does not alter charge-limit decisions, polling intervals, credentials, or remote command dispatch.

The earlier scooter artwork and its generation prompt remain available in the checkpoint commit before this redesign.

App and widget colors follow the system wallpaper palette on Android 12+, including the native dashboard battery drawing. Both follow system light/dark mode; older Android versions use standard Material palettes. Widget repaints on configuration, wallpaper, locale, clock, and app-update events use saved readings without adding API requests.

Sources: [Material dynamic colors](https://developer.android.com/develop/ui/compose/designsystems/material3#dynamic_color), [Android broadcast rules](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions).
