package io.ather.pro.ble

import com.google.gson.JsonParser
import java.util.UUID

/**
 * Turns notification payloads into vehicle values. The wire format of a real
 * scooter is not documented, so this stays deliberately permissive: raw hex is
 * logged for every packet, and two shapes are decoded today —
 *  - JSON lines like {"soc":82,"speed":41,"odo":1523.4,"charging":false}
 *  - compact binary where byte0=battery% and byte1=speed
 * Update [parse] once the scooter's real GATT payloads are captured.
 */
object BleParser {
    fun parse(uuid: UUID, data: ByteArray, current: BleVehicleSnapshot): BleVehicleSnapshot {
        if (data.isEmpty()) return current
        val time = System.currentTimeMillis()
        asAsciiJson(data)?.let { json ->
            return current.copy(
                connected = true,
                batteryPercent = json.intOrNull("soc", "battery", "batteryPercent") ?: current.batteryPercent,
                speedKmh = json.floatOrNull("speed", "speedKmh") ?: current.speedKmh,
                odoKm = json.floatOrNull("odo", "odometer", "odoKm") ?: current.odoKm,
                charging = json.boolOrNull("charging") ?: current.charging,
                lastUpdated = time
            )
        }
        var s = current.copy(connected = true, lastUpdated = time)
        val b0 = data[0].toInt() and 0xFF
        if (b0 in 0..100) s = s.copy(batteryPercent = b0)
        if (data.size >= 2) {
            val sp = data[1].toInt() and 0xFF
            if (sp in 0..120) s = s.copy(speedKmh = sp.toFloat())
        }
        return s
    }

    private fun asAsciiJson(data: ByteArray): com.google.gson.JsonObject? = try {
        val text = String(data, Charsets.US_ASCII).trim()
        if (text.startsWith("{") && text.endsWith("}")) JsonParser.parseString(text) as? com.google.gson.JsonObject else null
    } catch (_: Exception) { null }

    private fun com.google.gson.JsonObject.intOrNull(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { k -> get(k)?.takeUnless { it.isJsonNull }?.asInt }

    private fun com.google.gson.JsonObject.floatOrNull(vararg keys: String): Float? =
        keys.firstNotNullOfOrNull { k -> get(k)?.takeUnless { it.isJsonNull }?.asFloat }

    private fun com.google.gson.JsonObject.boolOrNull(vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { k -> get(k)?.takeUnless { it.isJsonNull }?.asBoolean }

    fun toHex(data: ByteArray) = data.joinToString(" ") { "%02X".format(it) }
}
