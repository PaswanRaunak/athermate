package io.ather.pro.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid

/**
 * Scans for the vehicle beacon. Faithful port of the real app's pairing scan
 * (com.ather.appbrewery.blueberry.scanner.BLEScanner.startBleScan with
 * isForPairing=true), which uses THREE hardware filters:
 *
 *   1. device name == token
 *   2. device name == token underscore-padded to (16 - scooterName.length)
 *   3. service data under FFA0 == token bytes
 *
 * The dash's identity lives primarily in its DEVICE NAME — a service-data-only
 * filter never sees it. Matching in the callback mirrors
 * ExtensionsKt.getMobileAdvertisementData / isAtherScanData: bidirectional
 * case-insensitive prefix match against service data OR device name, rejecting
 * matches that are really model names ("450", "Rizta", "Ather").
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
        val pad = BleVehicleProfile.scooterNamePadLength(SCOOTER_NAME)
        val (normalized, padded) = BleVehicleProfile.normalizeToken(myToken, SCOOTER_NAME)
        NORMALIZED = normalized

        // Ather's pairing filters — ORed by the stack.
        val filters = buildList {
            add(ScanFilter.Builder().setDeviceName(normalized).build())
            if (normalized.length < pad) add(ScanFilter.Builder().setDeviceName(padded).build())
            add(ScanFilter.Builder().setDeviceName(myToken).build())
            add(
                ScanFilter.Builder()
                    .setServiceData(ParcelUuid(BleVehicleProfile.BEACON_SERVICE_DATA_UUID), myToken.toByteArray(Charsets.UTF_8))
                    .build()
            )
        }
        // LOW_LATENCY (Ather: ScanSettings.Builder().setScanMode(2)).
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        val started = runCatching { scanner.startScan(filters, settings, callback) }.isSuccess
        // Safety net: unfiltered scan with the same callback so code-level name
        // matching can still catch firmware variants (padded/odd tokens).
        if (!started) runCatching { scanner.startScan(null, settings, callback) }
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
            val myToken = TOKEN.trim()
            if (myToken.isEmpty()) return

            // The dash may broadcast the plaintext token, the padded dash-name form,
            // or (reconnect beacons) the AES-truncated form — accept any of them.
            val candidates = listOf(myToken, NORMALIZED, ENCRYPTED)
                .filter { it.isNotBlank() }.distinct()
            val serviceData = record.serviceData.values.firstOrNull()
                ?.let { String(it, Charsets.UTF_8).trim() }
            val name = (record.deviceName ?: result.device?.name)?.trim()

            val dataMatch = serviceData != null && candidates.any { isTokenMatch(serviceData, it) }
            val nameMatch = !name.isNullOrEmpty() && candidates.any { isTokenMatch(name, it) }
            if (!dataMatch && !nameMatch) return

            val token = when {
                dataMatch -> serviceData!!
                else -> name!!
            }
            onBeaconFound(result.device, token, result.rssi, isBonded(result))
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onError("scan failed, errorCode=$errorCode")
        }
    }

    /**
     * ExtensionsKt.isAtherScanData(scanData, token): non-empty, prefix match in
     * either direction (case-insensitive), and the data is not a model name
     * ("450", "Rizta" / "Ather" when it is clearly the scooter, not the token).
     */
    private fun isTokenMatch(scanData: String, token: String): Boolean {
        if (scanData.isEmpty()) return false
        val lower = scanData.lowercase()
        if (BleVehicleProfile.SCOOTER_NAMES.any { lower.startsWith(it.lowercase()) } &&
            !token.lowercase().startsWith(lower.take(3))
        ) return false
        val t = token.lowercase()
        val s = lower
        return s.startsWith(t) || t.startsWith(s)
    }

    private fun isBonded(result: ScanResult): Boolean =
        runCatching { result.device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    companion object {
        /** Raw pairing token (from dash QR). */
        @Volatile var TOKEN: String = ""
            set(value) {
                field = value
                // Reconnect beacons carry AES(token)[4..15) instead of the plaintext.
                ENCRYPTED = if (value.isEmpty()) "" else VehicleBeaconAdvertiser.encryptToken(value)
            }
        /** AES-truncated variant of [TOKEN]. */
        @Volatile var ENCRYPTED: String = ""
            private set
        /** Whitespace-stripped, pad-truncated variant used for name filters. */
        @Volatile var NORMALIZED: String = ""
        /** Scooter model name; drives the dash-name pad length. */
        @Volatile var SCOOTER_NAME: String = BleVehicleProfile.DEFAULT_SCOOTER_NAME
    }
}
