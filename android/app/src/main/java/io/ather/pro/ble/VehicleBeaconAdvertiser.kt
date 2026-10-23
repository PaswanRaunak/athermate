package io.ather.pro.ble

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phone-side beacon advertiser, byte-faithful to the real app's BLEAdvertiser.java:
 *
 *   AdvertiseData = includeDeviceName + includeTxPowerLevel + addServiceData(uuid, token)
 *
 * NOTE: the real app does NOT put the service UUID in the UUID list. A 128-bit UUID
 * (18 B) + service-data-with-UUID (29 B) overflows the 31-byte legacy advertisement
 * and the stack rejects the entire advertisement (ADVERTISE_FAILED_DATA_TOO_LARGE)
 * — the beacon silently never goes on air. Service-data alone is what the dash needs.
 *
 * Crypto: BuildConfig values extracted from the real app (blueberry __4.6.2):
 * key "Ather#4532121212", IV "Ather@8765232323", AES/CBC, Base64[4..15).
 */
object VehicleBeaconAdvertiser {

    private const val SECRET_KEY = "Ather#4532121212"                       // 16 bytes
    private const val IV = "Ather@8765232323"                              // 16 bytes

    /** Wire the controller's log so on-air/failure status is visible in the UI. */
    @Volatile var logger: ((String) -> Unit)? = null

    private var currentCallback: AdvertiseCallback? = null

    fun startEncryptedToken(advertiser: BluetoothLeAdvertiser, token: String): String {
        val encrypted = encryptToken(token)
        start(advertiser, encrypted, timeoutMs = 0)
        return encrypted
    }

    fun startPlainToken(advertiser: BluetoothLeAdvertiser, token: String) {
        start(advertiser, token, BleVehicleProfile.FRESH_BEACON_DURATION_MS)
    }

    private fun start(
        advertiser: BluetoothLeAdvertiser,
        serviceData: String,
        timeoutMs: Int,
        includeTxPower: Boolean = true
    ) {
        stop(advertiser)
        // Ather BLEAdvertiser: mode(2)=LOW_LATENCY, connectable, txPower(3)=HIGH.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(timeoutMs)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        // Exactly the real app's packet — service data only, no UUID list.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(includeTxPower)
            .addServiceData(
                ParcelUuid(BleVehicleProfile.BEACON_SERVICE_DATA_UUID),
                serviceData.toByteArray(Charsets.UTF_8)
            )
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                logger?.invoke(
                    "beacon ON AIR: \"$serviceData\" (${serviceData.toByteArray(Charsets.UTF_8).size} B payload, " +
                        "connectable, timeout=${settingsInEffect?.timeout}s)"
                )
            }

            override fun onStartFailure(errorCode: Int) {
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE && includeTxPower) {
                    logger?.invoke("advertise rejected: data too large — retrying without tx-power field")
                    start(advertiser, serviceData, timeoutMs, includeTxPower = false)
                } else {
                    logger?.invoke("ADVERTISE FAILED (code=$errorCode): ${describe(errorCode)} — the dash CANNOT see this phone")
                }
            }
        }
        currentCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    fun stop(advertiser: BluetoothLeAdvertiser?) {
        currentCallback?.let { cb -> runCatching { advertiser?.stopAdvertising(cb) } }
        currentCallback = null
    }

    private fun describe(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "packet exceeds the 31-byte BLE limit"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "no free advertiser slot (another app holds it)"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal stack error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "advertising unsupported on this device"
        else -> "unknown"
    }

    /** AES/CBC/PKCS5 then Base64[4..15) — byte-identical policy to Ather's encryptToken. */
    fun encryptToken(token: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(SECRET_KEY.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(IV.toByteArray(Charsets.UTF_8)))
        val b64 = Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.DEFAULT)
        return if (b64.length >= 15) b64.substring(4, 15) else b64.take(11)
    }

    fun randomToken(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString { repeat(8) { append(alphabet[SecureRandom().nextInt(alphabet.length)]) } }
    }
}
