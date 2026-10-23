package io.ather.pro.ble

import java.util.UUID

/**
 * Ather "Blueberry" BLE profile, modeled 1:1 on the protocol recovered from
 * com.ather.appbrewery.blueberry.constants (see the reverse-engineering report).
 *
 * These values are the wire contract with the vehicle; do not change them
 * casually — payload types 0-9 and command bytes 0-18 must stay stable.
 */
object BleVehicleProfile {

    // ---- Beacon (advertisement) -------------------------------------------------
    /** Service-data UUID carried by the vehicle/owner beacon (QUICK_PAIR_DEVICE_VIN_UUID). */
    val BEACON_SERVICE_DATA_UUID: UUID =
        UUID.fromString("0000ffa0-0000-1000-8000-00805f9b34fb")
    val BEACON_SERVICE_DATA_UUID_ALT: UUID =
        UUID.fromString("0000ffa1-0000-1000-8000-00805f9b34fb")

    // ---- GATT services (vehicle side) --------------------------------------------
    val CALL_SERVICE: UUID   = UUID.fromString("69e318bc-1dd0-49b5-92cb-8540817f47a2")
    val MEDIA_SERVICE: UUID  = UUID.fromString("69e318de-1dd0-49b5-92cb-8540817f47a2")
    val SOCIAL_SERVICE: UUID = UUID.fromString("9f30e471-a96f-439e-acf6-04e1811917b3")
    val TBT_SERVICE: UUID    = UUID.fromString("bd617e62-44ca-11ef-9454-0242ac120002")
    val VOICE_SERVICE: UUID  = UUID.fromString("bd617e63-44ca-11ef-9454-0242ac120002")

    /** All custom services the vehicle exposes; the client subscribes in this order. */
    val SUBSCRIBE_ORDER: List<UUID> = listOf(CALL_SERVICE, MEDIA_SERVICE, SOCIAL_SERVICE, VOICE_SERVICE)

    // ---- Characteristics -----------------------------------------------------------
    /** Notify characteristic on every service. */
    val NOTIFY_CHAR: UUID = UUID.fromString("69e318be-1dd0-49b5-92cb-8540817f47a2")

    /** Command characteristic on every service. */
    val COMMAND_CHAR: UUID = UUID.fromString("69e318be-1d0d-49b5-92cb-8540817f47a2")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- Transport sizing -----------------------------------------------------------
    const val REQUEST_MTU = 517
    const val MAX_PAYLOAD_BYTES = 500

    // ---- Payload types: {"t": n, "data": {...}} -------------------------------------
    const val PAYLOAD_CONNECTION = 0
    const val PAYLOAD_PHONE_CALL = 1
    const val PAYLOAD_MEDIA = 2
    const val PAYLOAD_ALBUM_ART = 3
    const val PAYLOAD_WHATSAPP = 4
    const val PAYLOAD_COMMAND = 5
    const val PAYLOAD_TBT = 6
    const val PAYLOAD_VC_UPTIME = 7
    const val PAYLOAD_VOICE = 8
    const val PAYLOAD_TOAST = 9

    // ---- Command bytes (vehicle -> phone) --------------------------------------------
    const val CMD_STOP_NAVIGATION = 0
    const val CMD_MEDIA_PREVIOUS = 1
    const val CMD_MEDIA_TOGGLE = 2
    const val CMD_MEDIA_NEXT = 3
    const val CMD_VOLUME_UP = 4
    const val CMD_VOLUME_DOWN = 5
    const val CMD_CALL_ACCEPT = 6
    const val CMD_CALL_REJECT = 7
    const val CMD_CALL_REJECT_WITH_MESSAGE = 8
    const val CMD_UNPAIR = 9
    const val CMD_DISCONNECT_CLIENT = 16
    const val CMD_KEY_SWITCH_ON = 17       // ignition on — phone starts streaming state
    const val CMD_KEY_SWITCH_OFF = 18         // ignition off — phone clears pending payloads

    // ---- TBT maneuver codes ------------------------------------------------------------
    const val TBT_ERROR = 0
    const val TBT_DEST_REACHED = 2
    const val TBT_TURN_LEFT = 10
    const val TBT_TURN_RIGHT = 11
    const val TBT_UTURN_LEFT = 15
    const val TBT_ROUNDABOUT = 12
    const val TBT_CONTINUE_STRAIGHT = 9

    // ---- Pairing -------------------------------------------------------------------
    /** Pairing timeout (createBond -> BONDED must complete within this window). */
    const val PAIRING_TIMEOUT_MS = 20_000L
    /** Fresh-connection beacon lifetime; the reconnect beacon is un-timed. */
    const val FRESH_BEACON_DURATION_MS = 180_000

    // ---- Identity (extracted from the real Ather app, blueberry __4.6.2) -----------
    /** BluetoothServer.listOfScooter — names the dash recognizes as scooter models. */
    val SCOOTER_NAMES = listOf("Ather", "450", "Rizta")

    /** Vehicle model used to derive the dash-name pad length; Ather default implies 11. */
    const val DEFAULT_SCOOTER_NAME = "Ather"

    /**
     * DashboardBTClient.initDashboardBLE: `scooterNamePadLength = 16 - scooterName.length()`.
     * The dash advertises its name as the token truncated *or underscore-padded* to exactly
     * this many bytes.
     */
    fun scooterNamePadLength(scooterName: String = DEFAULT_SCOOTER_NAME): Int =
        (16 - scooterName.length).coerceAtLeast(1)

    /**
     * DashboardBTClient.setUserNameFromHMI: strip ALL whitespace, then truncate UTF-8
     * bytes to the pad length. Returns the normalized token plus its padded variant
     * (token + '_' repeated to exactly pad bytes) used for the name scan filter.
     */
    fun normalizeToken(rawToken: String, scooterName: String = DEFAULT_SCOOTER_NAME): Pair<String, String> {
        val pad = scooterNamePadLength(scooterName)
        val stripped = rawToken.replace(Regex("\\s"), "")
        val bytes = stripped.toByteArray(Charsets.UTF_8)
        val normalized = if (bytes.size > pad) String(bytes.copyOf(pad), Charsets.UTF_8) else stripped
        val padded = normalized.padEnd(pad, '_')
        return normalized to padded
    }

    /**
     * BluetoothClient.setDeviceName: the phone's own Bluetooth adapter name becomes
     * "<token>'s <scooterName>" (renamed only when different, capped at 19 UTF-8 bytes).
     * The dash uses this to recognise the owner's phone.
     */
    fun phoneBtName(token: String, scooterName: String = DEFAULT_SCOOTER_NAME): String {
        val full = "$token's $scooterName"
        val bytes = full.toByteArray(Charsets.UTF_8)
        return if (bytes.size > 19) String(bytes.copyOf(19), Charsets.UTF_8) else full
    }


    // ---- prefs --------------------------------------------------------------------
    const val PREFS_FILE = "vehicle_bt_prefs"
    const val PREF_TOKEN = "qrCodeToken"
    const val PREF_LAST_CONNECTED = "LastConnectedDeviceAddress"
    const val PREF_LINK_ENABLED = "vehicle_link_enabled"
}
