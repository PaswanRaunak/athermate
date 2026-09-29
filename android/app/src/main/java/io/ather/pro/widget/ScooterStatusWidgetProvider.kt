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

class ScooterStatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateAppWidget(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        updateAppWidget(context, manager, id)
    }

    companion object {
        @Suppress("DEPRECATION")
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val snapshot = DashboardWidgetSnapshot.load(context)
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(context, appWidgetId, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val now = System.currentTimeMillis()
            fun render(width: Float, height: Float) = WidgetRenderer.render(context, snapshot, width, height, now).apply {
                setOnClickPendingIntent(R.id.widget_root, pending)
            }
            val sizes = if (Build.VERSION.SDK_INT >= 31)
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()
                    .filter { it.width > 0 && it.height > 0 }.distinct().take(16) else emptyList()
            val views = if (Build.VERSION.SDK_INT >= 31 && sizes.isNotEmpty()) {
                RemoteViews(sizes.associateWith { render(it.width, it.height) })
            } else {
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).toFloat()
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 260).toFloat()
                val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth.toInt()).toFloat()
                val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight.toInt()).toFloat()
                RemoteViews(render(maxWidth, minHeight), render(minWidth, maxHeight))
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
