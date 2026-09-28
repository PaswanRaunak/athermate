package io.ather.pro.data.charging

import android.content.Context
import android.content.SharedPreferences
import io.ather.pro.domain.charging.ChargeLimitController

/**
 * Per-scooter charge-limit preferences (plain private prefs; not credentials).
 */
class ChargeLimitStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(vehicleUuid: String): ChargeLimitController.Snapshot {
        if (vehicleUuid.isBlank()) {
            return ChargeLimitController.Snapshot()
        }
        val enabled = prefs.getBoolean(keyEnabled(vehicleUuid), false)
        val percent = ChargeLimitController.clampPercent(
            prefs.getInt(keyPercent(vehicleUuid), ChargeLimitController.DEFAULT_PERCENT)
        )
        return if (enabled) {
            ChargeLimitController.Snapshot(
                enabled = true,
                percent = percent,
                status = ChargeLimitController.Status.MONITORING,
                message = "Phone-app automation — not a firmware charge limit.",
                armed = true
            )
        } else {
            ChargeLimitController.Snapshot(
                enabled = false,
                percent = percent,
                status = ChargeLimitController.Status.DISABLED,
                armed = false
            )
        }
    }

    fun save(vehicleUuid: String, enabled: Boolean, percent: Int) {
        if (vehicleUuid.isBlank()) return
        prefs.edit()
            .putBoolean(keyEnabled(vehicleUuid), enabled)
            .putInt(keyPercent(vehicleUuid), ChargeLimitController.clampPercent(percent))
            .apply()
    }

    companion object {
        const val PREFS_NAME = "ather_charge_limit"

        private fun keyEnabled(uuid: String) = "${uuid}_enabled"
        private fun keyPercent(uuid: String) = "${uuid}_percent"
    }
}
