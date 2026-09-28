package io.ather.pro.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.charging.ChargeLimitController

object DashboardWidgetUpdater {
    fun publish(context: Context, state: ScooterDashboardState, limit: ChargeLimitController.Snapshot = ChargeLimitController.Snapshot()) {
        val appContext = context.applicationContext
        if (state.telemetry == null && state.lastUpdated == null) return
        DashboardWidgetSnapshot.save(appContext, DashboardWidgetSnapshot.fromDashboard(state, limit))
        refreshAll(appContext)
    }

    fun clear(context: Context) {
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
