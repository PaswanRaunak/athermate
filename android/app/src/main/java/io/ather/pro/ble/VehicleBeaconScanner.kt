package io.ather.pro.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid

/**
 * Scans for the vehicle/owner beacon. Mirrors com.ather.appbrewery.blueberry
 * .beacon.BeamManager: the beacon carries SERVICE DATA under the profile UUID
 * whose UTF-8 content is the pairing token; the phone matches its own token
 * bidirectionally (Ather's `isAtherScanData` prefix matching).
 */
class VehicleBeaconScanner(
    private val onBeaconFound: (device: BluetoothDevice, token: String, rssi: Int, bonded: Boolean) -> Unit,
    private val onError: (message: String) -> Unit
) {
    private var scanning = false

    @Synchronized
    fun start(scanner: android.bluetooth.le.BluetoothLeScanner, myToken: String) {
        if (scanning) return
        TOKEN = myToken
        val filter1 = ScanFilter.Builder()
            .setServiceData(ParcelUuid(BleVehicleProfile.BEACON_SERVICE_DATA_UUID), null)
            .build()
        val filter2 = ScanFilter.Builder()
            .setServiceData(ParcelUuid(BleVehicleProfile.BEACON_SERVICE_DATA_UUID_ALT), null)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        runCatching {
            scanner.startScan(listOf(filter1, filter2), settings, callback)
        }.onFailure {
            runCatching { scanner.startScan(null, settings, callback) }
        }
    }

    @Synchronized
    fun stop(scanner: android.bluetooth.le.BluetoothLeScanner?) {
        if (!scanning) return
        runCatching { scanner?.stopScan(callback) }
        scanning = false
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val data = record.getServiceData(ParcelUuid(BleVehicleProfile.BEACON_SERVICE_DATA_UUID))
                ?: record.getServiceData(ParcelUuid(BleVehicleProfile.BEACON_SERVICE_DATA_UUID_ALT))
                ?: record.serviceData.values.firstOrNull()
                ?: return
            val token = String(data, Charsets.UTF_8).trim()
            if (token.isEmpty()) return

            val myToken = TOKEN.trim()
            if (myToken.isEmpty()) return

            // Ather's reconnect beacon carries the AES-truncated token instead of
            // the plaintext one, so match that variant too.
            val candidates = listOf(myToken, ENCRYPTED_TOKEN.trim()).filter { it.isNotEmpty() }
            val matches = candidates.any { candidate ->
                token.startsWith(candidate, ignoreCase = true) ||
                        candidate.startsWith(token, ignoreCase = true) ||
                        token.contains(candidate, ignoreCase = true) ||
                        candidate.contains(token, ignoreCase = true)
            }

            if (!matches) return
            onBeaconFound(result.device, token, result.rssi, isBonded(result))
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onError("scan failed, errorCode=$errorCode")
        }
    }

    private fun isBonded(result: ScanResult): Boolean =
        runCatching { result.device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    companion object {
        /** Set before scanning; Ather passes the logged-in user name as the token. */
        @Volatile var TOKEN: String = ""
            set(value) {
                field = value
                // Ather's reconnect beacon broadcasts AES(token)[4..15), not the plaintext.
                ENCRYPTED_TOKEN = if (value.isEmpty()) "" else VehicleBeaconAdvertiser.encryptToken(value)
            }
        /** AES-truncated variant of [TOKEN]. */
        @Volatile var ENCRYPTED_TOKEN: String = ""
            private set
    }
}
