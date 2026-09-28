package io.ather.pro.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.ather.pro.domain.model.ScooterDashboardState

object DashboardWidgetUpdater {
    fun publish(context: Context, state: ScooterDashboardState) {
        val appContext = context.applicationContext
        DashboardWidgetSnapshot.save(appContext, DashboardWidgetSnapshot.fromDashboard(state))
        refreshAll(appContext)
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
