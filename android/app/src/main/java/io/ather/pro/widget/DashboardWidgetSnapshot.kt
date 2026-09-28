package io.ather.pro.widget

import android.content.Context
import io.ather.pro.domain.range.RangeEstimator
import io.ather.pro.domain.charging.ChargeLimitController
import com.google.gson.Gson
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ScooterDashboardState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WidgetBatteryPoint(val timestamp: Long, val soc: Double)

/** Lightweight prefs snapshot for the home-screen widget (no Room/auth coupling). */
data class DashboardWidgetSnapshot(
    val socPercent: Double? = null,
    val rangeKm: Double? = null,
    val syncLabel: String = "Never synced",
    val connectionLabel: String = "OFFLINE",
    val updatedAtMs: Long = 0L,
    val modesText: String = "Mode ranges unavailable",
    val batteryHistory: List<WidgetBatteryPoint> = emptyList(),
    val chargeLabel: String = "Limit off",
    val limitPercent: Int? = null
) {
    val socText: String
        get() = socPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--"

    val rangeText: String
        get() = rangeKm?.let { String.format(Locale.US, "%.0f km", it) } ?: "Range --"

    companion object {
        private const val PREFS = "ather_dashboard_widget"
        private const val KEY_SOC = "soc"
        private const val KEY_RANGE = "range"
        private const val KEY_SYNC = "sync"
        private const val KEY_CONN = "conn"
        private const val KEY_UPDATED = "updated"

        fun fromDashboard(state: ScooterDashboardState, limit: ChargeLimitController.Snapshot = ChargeLimitController.Snapshot()): DashboardWidgetSnapshot {
            val telemetry = state.telemetry
            val range = RangeEstimator.current(telemetry)
            val sync = state.lastUpdated?.let {
                "Synced " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it))
            } ?: "Never synced"
            return DashboardWidgetSnapshot(
                socPercent = telemetry?.batterySoc?.takeIf { it.isFinite() },
                rangeKm = range?.takeIf { it.isFinite() && it >= 0.0 },
                syncLabel = sync,
                connectionLabel = when (state.connection) {
                    ConnectionStatus.CONNECTED -> "LIVE"
                    ConnectionStatus.CONNECTING -> "CONNECTING"
                    ConnectionStatus.DISCONNECTED -> "OFFLINE"
                    ConnectionStatus.ERROR -> "ERROR"
                },
                updatedAtMs = state.lastUpdated ?: 0L,
                modesText = RangeEstimator.modes(telemetry).take(6).chunked(2).joinToString("\n") { pair ->
                    pair.joinToString("  ·  ") { "${it.name} ${String.format(Locale.getDefault(), "%.0f", it.km)} km" }
                }.ifBlank { "Mode ranges unavailable" },
                batteryHistory = state.telemetryHistory.takeLast(120).map { WidgetBatteryPoint(it.timestamp, it.batterySoc) },
                chargeLabel = if (limit.enabled) "Limit ${limit.percent}% · ${limit.status.name.lowercase()}" else "Limit off",
                limitPercent = limit.percent.takeIf { limit.enabled }
            )
        }

        fun load(context: Context): DashboardWidgetSnapshot {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val socBits = prefs.getLong(KEY_SOC, Long.MIN_VALUE)
            val rangeBits = prefs.getLong(KEY_RANGE, Long.MIN_VALUE)
            return DashboardWidgetSnapshot(
                socPercent = socBits.takeIf { it != Long.MIN_VALUE }?.let { Double.fromBits(it) },
                rangeKm = rangeBits.takeIf { it != Long.MIN_VALUE }?.let { Double.fromBits(it) },
                syncLabel = prefs.getString(KEY_SYNC, "Never synced") ?: "Never synced",
                connectionLabel = prefs.getString(KEY_CONN, "OFFLINE") ?: "OFFLINE",
                updatedAtMs = prefs.getLong(KEY_UPDATED, 0L),
                modesText = prefs.getString("modes", "Mode ranges unavailable").orEmpty(),
                batteryHistory = runCatching { Gson().fromJson(prefs.getString("history", "[]"), Array<WidgetBatteryPoint>::class.java).toList() }.getOrDefault(emptyList()),
                chargeLabel = prefs.getString("charge_label", "Limit off").orEmpty(),
                limitPercent = prefs.getInt("limit_percent", -1).takeIf { it in 50..100 }
            )
        }

        fun save(context: Context, snapshot: DashboardWidgetSnapshot) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    if (snapshot.socPercent != null) {
                        putLong(KEY_SOC, snapshot.socPercent.toRawBits())
                    } else {
                        remove(KEY_SOC)
                    }
                    if (snapshot.rangeKm != null) {
                        putLong(KEY_RANGE, snapshot.rangeKm.toRawBits())
                    } else {
                        remove(KEY_RANGE)
                    }
                    putString(KEY_SYNC, snapshot.syncLabel)
                    putString(KEY_CONN, snapshot.connectionLabel)
                    putLong(KEY_UPDATED, snapshot.updatedAtMs)
                    putString("modes", snapshot.modesText)
                    putString("history", Gson().toJson(snapshot.batteryHistory))
                    putString("charge_label", snapshot.chargeLabel)
                    putInt("limit_percent", snapshot.limitPercent ?: -1)
                }
                .apply()
        }
    }
}
