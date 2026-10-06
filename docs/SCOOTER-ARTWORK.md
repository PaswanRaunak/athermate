# Energy artwork

When a drawing has been supplied for the signed-in model, the home hero shows that original side-view illustration and the account name in large type. `ScooterArtwork` picks the file from the detected model (`modelForRange`) and the saved colour, or the colour on the vehicle profile. An unknown colour uses that model's default drawing. A model with no supplied drawing keeps the battery hero below. The drawings are simplified originals in `res/drawable/scooter_*.xml`, with the same paths as SVG in `assets/scooter-art/`. They are not official product photos. `tools/generate_scooter_art.py` regenerates both from one set of paths.

The battery visual is drawn by `ui/visuals/BatteryArtwork.kt`. Its fill and outer ring use the reported state of charge. A Material tertiary-color marker indicates the enabled limit, matching the limit label below the dashboard hero. Missing readings leave the battery empty without implying a measured 0%. The launcher icon uses the original scooter mark.

Charging adds a small energy flow and soft surface sheen; it never animates the reported percentage upwards. Motion runs only when the dashboard is visible, the activity is resumed, system animations are enabled, and the connected battery report is no more than two minutes old. Saved charging readings are labelled as saved and do not animate. Paused/stopped states remain still.

Widgets use the classic Material layout: an Athr+ header, battery percentage and current range above a single list of mode ranges, with no battery illustration. When the limiter is enabled, a slim bar shows the reported battery fill and a Material tertiary-color cutoff marker. It renders only when their existing snapshot updater runs, with no animation timer or extra API requests. This visual change does not alter charge-limit decisions, polling intervals, credentials, or remote command dispatch.

The earlier scooter artwork and its generation prompt remain available in the checkpoint commit before this redesign.

App and widget colors follow the system wallpaper palette on Android 12+, including the native dashboard battery drawing. Both follow system light/dark mode; older Android versions use standard Material palettes. Widget repaints on configuration, wallpaper, locale, clock, and app-update events use saved readings without adding API requests.

Sources: [Material dynamic colors](https://developer.android.com/develop/ui/compose/designsystems/material3#dynamic_color), [Android broadcast rules](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions).
