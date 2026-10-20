package io.ather.pro.ble

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire contract with the vehicle dash: payload envelope shape, field
 * names, command bytes and profile UUIDs recovered from the Ather app must
 * not drift.
 */
class VehiclePayloadCodecTest {

    private fun roundTrip(bytes: ByteArray): JsonObject {
        val decoded = VehiclePayloadCodec.decode(bytes)!!
        return decoded.data
    }

    @Test
    fun envelopeCarriesTypeAndData() {
        val bytes = VehiclePayloadCodec.encode(3, JsonObject().apply { addProperty("k", "v") })
        val json = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        assertEquals(3, json["t"].asInt)
        assertEquals("v", json["data"].asJsonObject["k"].asString)
        assertEquals(2, json.keySet().size)
    }

    @Test
    fun connectionHandshakeFields() {
        val data = roundTrip(VehiclePayloadCodec.connection("Pixel 8", "Google", "Android 15", "athermate/1.0"))
        assertEquals("Pixel 8", data["btName"].asString)
        assertEquals("Google", data["modelName"].asString)
        assertEquals("Android 15", data["os"].asString)
        assertEquals("athermate/1.0", data["libVersion"].asString)
        assertEquals(2, data["smsPermission"].asInt)
    }

    @Test
    fun phoneCallFields() {
        val data = roundTrip(VehiclePayloadCodec.phoneCall("Mom", 1, true))
        assertEquals("Mom", data["callerName"].asString)
        assertEquals(1, data["state"].asInt)
        assertTrue(data["whatsapp"].asBoolean)
    }

    @Test
    fun mediaUsesShortKeys() {
        val data = roundTrip(VehiclePayloadCodec.media("Track", "Artist", "playing", 42, 210))
        assertEquals("Track", data["t"].asString)
        assertEquals("Artist", data["st"].asString)
        assertEquals("playing", data["state"].asString)
        assertEquals(42, data["elapsedTime"].asInt)
        assertEquals(210, data["trackTime"].asInt)
    }

    @Test
    fun whatsappUsesShortKeys() {
        val data = roundTrip(VehiclePayloadCodec.whatsapp("Mom", "Ride safe!", true))
        assertEquals("Mom", data["f"].asString)
        assertEquals("Ride safe!", data["m"].asString)
        assertTrue(data["g"].asBoolean)
    }

    @Test
    fun tbtFields() {
        val data = roundTrip(VehiclePayloadCodec.tbt(BleVehicleProfile.TBT_TURN_LEFT, "Turn left", 180))
        assertEquals(BleVehicleProfile.TBT_TURN_LEFT, data["maneuverId"].asInt)
        assertEquals("Turn left", data["instruction"].asString)
        assertEquals(180, data["remainingDistance"].asInt)
    }

    @Test
    fun uptimeAndToast() {
        assertEquals(1234L, roundTrip(VehiclePayloadCodec.vcUptime(1234))["uptime"].asLong)
        assertEquals("hi", roundTrip(VehiclePayloadCodec.toast("hi"))["message"].asString)
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(VehiclePayloadCodec.decode("not json".toByteArray()))
        assertNull(VehiclePayloadCodec.decode("{\"no_t\":1}".toByteArray()))
    }

    @Test
    fun payloadTypesAreTheAtherContract() {
        assertEquals(0, BleVehicleProfile.PAYLOAD_CONNECTION)
        assertEquals(1, BleVehicleProfile.PAYLOAD_PHONE_CALL)
        assertEquals(2, BleVehicleProfile.PAYLOAD_MEDIA)
        assertEquals(3, BleVehicleProfile.PAYLOAD_ALBUM_ART)
        assertEquals(4, BleVehicleProfile.PAYLOAD_WHATSAPP)
        assertEquals(5, BleVehicleProfile.PAYLOAD_COMMAND)
        assertEquals(6, BleVehicleProfile.PAYLOAD_TBT)
        assertEquals(7, BleVehicleProfile.PAYLOAD_VC_UPTIME)
        assertEquals(8, BleVehicleProfile.PAYLOAD_VOICE)
        assertEquals(9, BleVehicleProfile.PAYLOAD_TOAST)
    }

    @Test
    fun commandBytesAreTheAtherContract() {
        assertEquals(0, BleVehicleProfile.CMD_STOP_NAVIGATION)
        assertEquals(1, BleVehicleProfile.CMD_MEDIA_PREVIOUS)
        assertEquals(2, BleVehicleProfile.CMD_MEDIA_TOGGLE)
        assertEquals(3, BleVehicleProfile.CMD_MEDIA_NEXT)
        assertEquals(4, BleVehicleProfile.CMD_VOLUME_UP)
        assertEquals(5, BleVehicleProfile.CMD_VOLUME_DOWN)
        assertEquals(6, BleVehicleProfile.CMD_CALL_ACCEPT)
        assertEquals(7, BleVehicleProfile.CMD_CALL_REJECT)
        assertEquals(8, BleVehicleProfile.CMD_CALL_REJECT_WITH_MESSAGE)
        assertEquals(9, BleVehicleProfile.CMD_UNPAIR)
        assertEquals(16, BleVehicleProfile.CMD_DISCONNECT_CLIENT)
        assertEquals(17, BleVehicleProfile.CMD_KEY_SWITCH_ON)
        assertEquals(18, BleVehicleProfile.CMD_KEY_SWITCH_OFF)
    }

    @Test
    fun profileUuidsAreTheAtherContract() {
        assertEquals("0000ffa0-0000-1000-8000-00805f9b34fb", BleVehicleProfile.BEACON_SERVICE_DATA_UUID.toString())
        assertEquals("0000ffa1-0000-1000-8000-00805f9b34fb", BleVehicleProfile.BEACON_SERVICE_DATA_UUID_ALT.toString())
        assertEquals("69e318bc-1dd0-49b5-92cb-8540817f47a2", BleVehicleProfile.CALL_SERVICE.toString())
        assertEquals("69e318de-1dd0-49b5-92cb-8540817f47a2", BleVehicleProfile.MEDIA_SERVICE.toString())
        assertEquals("9f30e471-a96f-439e-acf6-04e1811917b3", BleVehicleProfile.SOCIAL_SERVICE.toString())
        assertEquals("bd617e62-44ca-11ef-9454-0242ac120002", BleVehicleProfile.TBT_SERVICE.toString())
        assertEquals("bd617e63-44ca-11ef-9454-0242ac120002", BleVehicleProfile.VOICE_SERVICE.toString())
        assertEquals("69e318be-1dd0-49b5-92cb-8540817f47a2", BleVehicleProfile.NOTIFY_CHAR.toString())
        assertEquals("69e318be-1d0d-49b5-92cb-8540817f47a2", BleVehicleProfile.COMMAND_CHAR.toString())
    }

    @Test
    fun transportSizingMatchesAther() {
        assertEquals(517, BleVehicleProfile.REQUEST_MTU)
        assertEquals(500, BleVehicleProfile.MAX_PAYLOAD_BYTES)
        assertEquals(listOf(
            BleVehicleProfile.CALL_SERVICE, BleVehicleProfile.MEDIA_SERVICE,
            BleVehicleProfile.SOCIAL_SERVICE, BleVehicleProfile.VOICE_SERVICE
        ), BleVehicleProfile.SUBSCRIBE_ORDER)
    }
}
