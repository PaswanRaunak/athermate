package io.ather.pro.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import io.ather.pro.MainActivity
import io.ather.pro.R

/**
 * Charging-progress widget: one big percentage, fill bar and ETA. The same
 * provider also serves the lock screen on Android 15 (keyguard category), which
 * is why rendering stays simple and tap targets just open the app.
 */
class ChargingWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            DashboardWidgetUpdater.enqueueManualRefresh(context)
            DashboardWidgetUpdater.refreshAll(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateAppWidget(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        updateAppWidget(context, manager, id)
    }

    companion object {
        private const val ACTION_REFRESH = "io.ather.pro.widget.CHARGING_REFRESH"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val snapshot = DashboardWidgetSnapshot.load(context)
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(context, appWidgetId + 1, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val refresh = PendingIntent.getBroadcast(context, appWidgetId + 1,
                Intent(context, ChargingWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val now = System.currentTimeMillis()
            fun render(width: Float, height: Float) =
                WidgetRenderer.renderCharging(context, snapshot, width, height, now).apply {
                    setOnClickPendingIntent(R.id.widget_charge_root, pending)
                }
            val sizes = if (Build.VERSION.SDK_INT >= 31)
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()
                    .filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }.distinct().take(16) else emptyList()
            val views = if (Build.VERSION.SDK_INT >= 31 && sizes.isNotEmpty()) {
                RemoteViews(sizes.associateWith { render(it.width, it.height) })
            } else {
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110).coerceAtLeast(100).toFloat()
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).coerceAtLeast(100).toFloat()
                render(minWidth, minHeight)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
