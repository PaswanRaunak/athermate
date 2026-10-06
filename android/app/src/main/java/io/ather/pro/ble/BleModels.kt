package io.ather.pro.ble

/** Connection lifecycle of the direct BLE link. */
enum class BleConnection { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/** A device seen during a scan; [scooterLike] flags name/service matches for known scooter patterns. */
data class BleDeviceUi(
    val name: String,
    val address: String,
    val rssi: Int,
    val scooterLike: Boolean
)

/** Latest values decoded from scooter BLE notifications; nulls mean "no packet yet". */
data class BleVehicleSnapshot(
    val connected: Boolean = false,
    val batteryPercent: Int? = null,
    val speedKmh: Float? = null,
    val odoKm: Float? = null,
    val charging: Boolean? = null,
    val lastUpdated: Long = 0L
)

/** Everything the Bluetooth screen renders. */
data class BleUiState(
    val adapterPresent: Boolean = false,
    val adapterEnabled: Boolean = false,
    val connection: BleConnection = BleConnection.DISCONNECTED,
    val connectingTo: String? = null,
    val devices: List<BleDeviceUi> = emptyList(),
    val snapshot: BleVehicleSnapshot = BleVehicleSnapshot(),
    val logs: List<String> = emptyList()
)
