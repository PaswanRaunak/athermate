package io.ather.pro.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.charging.ChargeLimitController

object DashboardWidgetUpdater {
    private var previousSnapshot: DashboardWidgetSnapshot? = null
    private var previousStale: Boolean? = null
    fun publish(context: Context, state: ScooterDashboardState, limit: ChargeLimitController.Snapshot = ChargeLimitController.Snapshot()) {
        val appContext = context.applicationContext
        if (state.telemetry == null && state.lastUpdated == null) return
        val snapshot = DashboardWidgetSnapshot.fromDashboard(state, limit)
        val stale = System.currentTimeMillis() - snapshot.updatedAtMs > 60_000L
        if (snapshot == previousSnapshot && stale == previousStale) return
        previousSnapshot = snapshot
        previousStale = stale
        DashboardWidgetSnapshot.save(appContext, snapshot)
        refreshAll(appContext)
    }

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
