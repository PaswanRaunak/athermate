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
        if (!enabled) return ChargeLimitController.Snapshot(percent = percent)
        val status = runCatching {
            ChargeLimitController.Status.valueOf(prefs.getString("${vehicleUuid}_status", "MONITORING")!!)
        }.getOrDefault(ChargeLimitController.Status.MONITORING)
        return ChargeLimitController.Snapshot(
            enabled = true,
            percent = percent,
            status = status,
            armed = prefs.getBoolean("${vehicleUuid}_armed", true),
            pendingSinceMs = prefs.getLong("${vehicleUuid}_pending_since", -1L).takeIf { it > 0 },
            attempts = prefs.getInt("${vehicleUuid}_attempts", 0),
            lastAttemptMs = prefs.getLong("${vehicleUuid}_last_attempt", -1L).takeIf { it > 0 },
            message = prefs.getString("${vehicleUuid}_message", null)
        )
    }

    /** Persist the command latch before dispatch so process recreation cannot send a duplicate. */
    fun save(vehicleUuid: String, snapshot: ChargeLimitController.Snapshot): Boolean {
        if (vehicleUuid.isBlank()) return false
        return prefs.edit()
            .putBoolean(keyEnabled(vehicleUuid), snapshot.enabled)
            .putInt(keyPercent(vehicleUuid), ChargeLimitController.clampPercent(snapshot.percent))
            .putString("${vehicleUuid}_status", snapshot.status.name)
            .putBoolean("${vehicleUuid}_armed", snapshot.armed)
            .putLong("${vehicleUuid}_pending_since", snapshot.pendingSinceMs ?: -1L)
            .putString("${vehicleUuid}_message", snapshot.message)
            .putInt("${vehicleUuid}_attempts", snapshot.attempts)
            .putLong("${vehicleUuid}_last_attempt", snapshot.lastAttemptMs ?: -1L)
            .commit()
    }

    companion object {
        const val PREFS_NAME = "ather_charge_limit"

        private fun keyEnabled(uuid: String) = "${uuid}_enabled"
        private fun keyPercent(uuid: String) = "${uuid}_percent"
    }
}
