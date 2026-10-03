# Energy artwork

The dashboard and widgets use a model-independent battery visual drawn by `ui/visuals/BatteryArtwork.kt`. It replaces the illustrative 450X image. The scooter name still comes from the profile or selected model; the artwork works for any model without image mappings.

The battery fill and outer ring use the reported state of charge. An amber marker indicates the enabled limit, matching the limit label below the dashboard hero. Missing readings leave the battery empty without implying a measured 0%. The launcher icon uses the same battery and emerald palette.

Charging adds a small energy flow and soft surface sheen; it never animates the reported percentage upwards. Motion runs only when the dashboard is visible, the activity is resumed, system animations are enabled, and the connected battery report is no more than two minutes old. Saved charging readings are labelled as saved and do not animate. Paused/stopped states remain still.

Widgets render the same artwork into a static bitmap when their existing snapshot updater runs. They do not have an animation timer or extra API requests. This visual change does not alter charge-limit decisions, polling intervals, credentials, or remote command dispatch.

The earlier scooter artwork and its generation prompt remain available in the checkpoint commit before this redesign.
