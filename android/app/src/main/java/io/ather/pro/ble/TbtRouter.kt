package io.ather.pro.ble

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turn-by-turn routing for the dash — the piece the original app gets from its
 * Mappls engine, provided here by OSRM's public demo server (free, no key).
 *
 * Given origin + destination it returns the ordered maneuver steps, each mapped
 * to the dash's maneuverId codes (0-25, from the decompiled BLEConstants.TBT_*),
 * so the dashboard renders real turn arrows instead of a distance countdown.
 */
object TbtRouter {

    data class Step(
        val maneuverId: Int,
        val instruction: String,
        val distanceMeters: Double,
        val lat: Double,
        val lng: Double
    )

    data class Route(val steps: List<Step>, val totalMeters: Double)

    suspend fun fetch(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Route? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("https://router.project-osrm.org/route/v1/driving/" +
                    "$fromLng,$fromLat;$toLng,$toLat?overview=false&steps=true&alternatives=false")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "AtherMate/1.3")
                val body = conn.inputStream.use { it.readBytes().decodeToString() }
                conn.disconnect()
                parse(body)
            }.getOrNull()
        }

    /** Parses OSRM's route/v1 JSON into dash-ready steps. */
    fun parse(json: String): Route? = runCatching {
        val root = org.json.JSONObject(json)
        val route = root.getJSONArray("routes").getJSONObject(0)
        val stepsJson = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        val steps = ArrayList<Step>(stepsJson.length())
        for (i in 0 until stepsJson.length()) {
            val s = stepsJson.getJSONObject(i)
            val man = s.getJSONObject("maneuver")
            val type = man.optString("type", "")
            val modifier = man.optString("modifier", "")
            val exit = man.optInt("exit", 0)
            val loc = man.getJSONArray("location")
            val road = s.optString("name", "").ifBlank { "" }
            val dist = s.optDouble("distance", 0.0)
            steps.add(
                Step(
                    maneuverId = maneuverId(type, modifier),
                    instruction = instruction(type, modifier, road, exit),
                    distanceMeters = dist,
                    lat = loc.getDouble(1),
                    lng = loc.getDouble(0)
                )
            )
        }
        if (steps.isEmpty()) null else Route(steps, route.optDouble("distance", 0.0))
    }.getOrNull()

    /** OSRM maneuver type+modifier -> decompiled BLEConstants.TBT_* codes. */
    fun maneuverId(type: String, modifier: String?): Int = when (type) {
        "depart" -> BleVehicleProfile.TBT_LOCATION_STARTED
        "arrive" -> BleVehicleProfile.TBT_DEST_REACHED
        "roundabout", "rotary", "roundabout turn" -> when (modifier) {
            "left" -> BleVehicleProfile.TBT_ROUNDABOUT_LEFT
            "right" -> BleVehicleProfile.TBT_ROUNDABOUT_RIGHT
            else -> BleVehicleProfile.TBT_IND_ROUNDABOUT
        }
        "exit roundabout", "exit rotary" -> BleVehicleProfile.TBT_IND_EXIT
        "off ramp" -> when (modifier) {
            "left" -> BleVehicleProfile.TBT_RAMP_LEFT
            "right" -> BleVehicleProfile.TBT_RAMP_RIGHT
            else -> BleVehicleProfile.TBT_IND_EXIT
        }
        "on ramp" -> when (modifier) {
            "left" -> BleVehicleProfile.TBT_RAMP_LEFT
            else -> BleVehicleProfile.TBT_RAMP_RIGHT
        }
        "fork" -> when (modifier) {
            "left", "sharp left", "slight left" -> BleVehicleProfile.TBT_FORK_LEFT
            else -> BleVehicleProfile.TBT_FORK_RIGHT
        }
        "merge" -> BleVehicleProfile.TBT_MERGE
        else -> when (modifier) {
            "uturn" -> BleVehicleProfile.TBT_UTURN_LEFT
            "sharp left" -> BleVehicleProfile.TBT_SHARP_LEFT
            "sharp right" -> BleVehicleProfile.TBT_SHARP_RIGHT
            "slight left" -> BleVehicleProfile.TBT_SLIGHT_LEFT
            "slight right" -> BleVehicleProfile.TBT_SLIGHT_RIGHT
            "left" -> BleVehicleProfile.TBT_TURN_LEFT
            "right" -> BleVehicleProfile.TBT_TURN_RIGHT
            else -> BleVehicleProfile.TBT_CONTINUE_STRAIGHT
        }
    }

    /** Human instruction for the dash line, built like a navigation app would. */
    fun instruction(type: String, modifier: String?, road: String, exit: Int): String {
        val onto = if (road.isNotBlank()) " onto $road" else ""
        val dir = when (modifier) {
            "left" -> "left"; "right" -> "right"
            "sharp left" -> "sharp left"; "sharp right" -> "sharp right"
            "slight left" -> "slightly left"; "slight right" -> "slightly right"
            "uturn" -> "around"
            else -> ""
        }
        return when (type) {
            "depart" -> "Start riding"
            "arrive" -> "You have arrived"
            "roundabout", "rotary", "roundabout turn" ->
                if (exit > 0) "At roundabout take exit $exit" else "At the roundabout"
            "exit roundabout", "exit rotary" -> "Exit the roundabout"
            "off ramp" -> "Take the exit" + (modifier?.let { " $it" } ?: "")
            "on ramp" -> "Take the ramp" + (modifier?.let { " $it" } ?: "")
            "fork" -> "Keep " + (dir.ifBlank { "straight" })
            "merge" -> "Merge" + onto
            "new name", "continue" -> "Continue" + onto
            "turn" -> if (modifier == "uturn") "Make a U-turn" + onto else "Turn $dir" + onto
            else -> if (dir.isNotBlank()) "Turn $dir" + onto else "Continue" + onto
        }
    }
}
