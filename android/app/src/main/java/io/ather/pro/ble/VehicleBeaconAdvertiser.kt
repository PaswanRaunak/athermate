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
 * Phone-side beacon advertiser, mirroring BluetoothServer.advertiseBeacon:
 *  - fresh connection: plaintext token, 180 s window
 *  - reconnect: AES/CBC-truncated token, un-timed
 *
 * Ather bakes a static key/IV into BuildConfig (AESHelper) and truncates the
 * Base64 to chars 4..15 so the payload fits an advertisement. We reproduce the
 * exact shape — obfuscation, not security; the link itself rides OS LE pairing.
 */
object VehicleBeaconAdvertiser {

    // Same design as Ather's BuildConfig.SECRET_KEY_STRING / IV_PARAMETER_STRING.
    private const val SECRET_KEY = "athermate-bt-key"                       // 16 bytes
    private const val IV = "0123456789abcdef"                              // 16 bytes

    private var currentCallback: AdvertiseCallback? = null

    fun startEncryptedToken(advertiser: BluetoothLeAdvertiser, token: String): String {
        val encrypted = encryptToken(token)
        start(advertiser, encrypted, timeoutMs = 0)
        return encrypted
    }

    fun startPlainToken(advertiser: BluetoothLeAdvertiser, token: String) {
        start(advertiser, token, BleVehicleProfile.FRESH_BEACON_DURATION_MS)
    }

    private fun start(advertiser: BluetoothLeAdvertiser, serviceData: String, timeoutMs: Int) {
        stop(advertiser)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(timeoutMs)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleVehicleProfile.BEACON_SERVICE_DATA_UUID))
            .addServiceData(
                ParcelUuid(BleVehicleProfile.BEACON_SERVICE_DATA_UUID),
                serviceData.toByteArray(Charsets.UTF_8)
            )
            .setIncludeDeviceName(false)
            .build()
        val callback = object : AdvertiseCallback() {}
        currentCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    fun stop(advertiser: BluetoothLeAdvertiser?) {
        currentCallback?.let { cb -> runCatching { advertiser?.stopAdvertising(cb) } }
        currentCallback = null
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
