package io.ather.pro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.ather.pro.ble.BleVehicleProfile

/**
 * Restores the vehicle BLE link after reboot — mirrors Ather's
 * BluetoothAndBootReceiverListener. The service start triggers the controller's
 * reconnect path (last bonded vehicle, then beacon scan).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs: SharedPreferences = context.getSharedPreferences(BleVehicleProfile.PREFS_FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(BleVehicleProfile.PREF_LINK_ENABLED, false)) return
        VehicleLinkService.start(context)
    }
}
