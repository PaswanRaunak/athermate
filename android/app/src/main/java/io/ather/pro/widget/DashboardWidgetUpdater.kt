package io.ather.pro.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.charging.ChargeLimitController

object DashboardWidgetUpdater {
    private var previousSnapshot: DashboardWidgetSnapshot? = null
    private var previousStale: Boolean? = null
    @Synchronized
    fun publish(context: Context, state: ScooterDashboardState, limit: ChargeLimitController.Snapshot = ChargeLimitController.Snapshot()) {
        val appContext = context.applicationContext
        val incoming = DashboardWidgetSnapshot.fromDashboard(state, limit)
        // Limiter changes must reach the widget even while waiting for a new scooter reading.
        val snapshot = if (state.telemetry == null && state.lastUpdated == null) {
            val saved = previousSnapshot ?: DashboardWidgetSnapshot.load(appContext)
            saved.copy(connectionLabel = incoming.connectionLabel, limitPercent = incoming.limitPercent,
                chargeLabel = incoming.chargeLabel, estimatedStopAtMs = incoming.estimatedStopAtMs)
        } else incoming
        val stale = System.currentTimeMillis() - snapshot.updatedAtMs > 60_000L
        if (snapshot == previousSnapshot && stale == previousStale) return
        previousSnapshot = snapshot
        previousStale = stale
        DashboardWidgetSnapshot.save(appContext, snapshot)
        refreshAll(appContext)
    }

    @Synchronized
    fun clear(context: Context) {
        previousSnapshot = null
        previousStale = null
        DashboardWidgetSnapshot.save(context, DashboardWidgetSnapshot())
        refreshAll(context)
    }

    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(appContext, ScooterStatusWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return
        for (id in ids) {
            ScooterStatusWidgetProvider.updateAppWidget(appContext, manager, id)
        }
    }
}
