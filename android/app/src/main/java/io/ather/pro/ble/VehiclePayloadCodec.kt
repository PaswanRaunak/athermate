package io.ather.pro.ble

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Wire codec for the payload envelope recovered from
 * com.ather.appbrewery.blueberry.client.AndroidClient.extractPayload:
 * every notification is JSON {"t": <type 0..9>, "data": {...}} and every
 * vehicle command is a single raw byte (BleVehicleProfile.CMD_*).
 *
 * Implemented on Gson (the app's JSON library) so the codec is covered by
 * plain JVM unit tests; the bytes on the wire are identical to org.json output
 * for these flat objects.
 */
object VehiclePayloadCodec {

    data class Payload(val type: Int, val data: JsonObject)

    fun encode(type: Int, data: JsonObject): ByteArray {
        val envelope = JsonObject()
        envelope.addProperty("t", type)
        envelope.add("data", data)
        return envelope.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): Payload? = runCatching {
        val obj = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        Payload(obj.get("t").asInt, obj.getAsJsonObject("data") ?: JsonObject())
    }.getOrNull()

    // ---- Typed helpers for the payloads the phone pushes to the dash -----------------

    /** t=0 — handshake sent right after subscribe, mirrors ConnectionModel fields. */
    fun connection(btName: String, model: String, os: String, libVersion: String): ByteArray =
        encode(
            BleVehicleProfile.PAYLOAD_CONNECTION,
            JsonObject().apply {
                addProperty("btName", btName)
                addProperty("modelName", model)
                addProperty("os", os)
                addProperty("libVersion", libVersion)
                addProperty("smsPermission", 2)
            }
        )

    /** t=1 — incoming call (state: 1 ringing / 2 offhook / 0 idle). */
    fun phoneCall(callerName: String, state: Int, whatsapp: Boolean): ByteArray =
        encode(
            BleVehicleProfile.PAYLOAD_PHONE_CALL,
            JsonObject().apply {
                addProperty("callerName", callerName)
                addProperty("state", state)
                addProperty("whatsapp", whatsapp)
            }
        )

    /** t=2 — now playing. */
    fun media(track: String, artist: String, state: String, elapsedSec: Int, trackTimeSec: Int): ByteArray =
        encode(
            BleVehicleProfile.PAYLOAD_MEDIA,
            JsonObject().apply {
                addProperty("t", track)
                addProperty("st", artist)
                addProperty("state", state)
                addProperty("elapsedTime", elapsedSec)
                addProperty("trackTime", trackTimeSec)
            }
        )

    /** t=4 — WhatsApp-style message preview (f=from, m=message, g=group). */
    fun whatsapp(from: String, message: String, group: Boolean): ByteArray =
        encode(
            BleVehicleProfile.PAYLOAD_WHATSAPP,
            JsonObject().apply {
                addProperty("f", from)
                addProperty("m", message)
                addProperty("g", group)
            }
        )

    /** t=6 — turn-by-turn navigation instruction. */
    fun tbt(maneuverId: Int, instruction: String, remainingDistanceMeters: Int): ByteArray =
        encode(
            BleVehicleProfile.PAYLOAD_TBT,
            JsonObject().apply {
                addProperty("maneuverId", maneuverId)
                addProperty("instruction", instruction)
                addProperty("remainingDistance", remainingDistanceMeters)
            }
        )

    /** t=7 — voice-channel uptime keepalive ping. */
    fun vcUptime(uptimeSec: Long): ByteArray =
        encode(BleVehicleProfile.PAYLOAD_VC_UPTIME, JsonObject().apply { addProperty("uptime", uptimeSec) })

    /** t=9 — dash toast. */
    fun toast(message: String): ByteArray =
        encode(BleVehicleProfile.PAYLOAD_TOAST, JsonObject().apply { addProperty("message", message) })
}
