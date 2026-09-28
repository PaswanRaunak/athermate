package io.ather.pro.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.ather.pro.MainActivity
import io.ather.pro.R

class ScooterStatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, id)
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val snapshot = DashboardWidgetSnapshot.load(context)
            val views = RemoteViews(context.packageName, R.layout.widget_scooter_status).apply {
                setTextViewText(R.id.widget_soc, snapshot.socText)
                setTextViewText(R.id.widget_range, snapshot.rangeText)
                setTextViewText(R.id.widget_sync, snapshot.syncLabel)
                setTextViewText(R.id.widget_connection, snapshot.connectionLabel)
                setContentDescription(
                    R.id.widget_root,
                    "Ather Pro widget. Battery ${snapshot.socText}, ${snapshot.rangeText}, " +
                        "${snapshot.connectionLabel}, ${snapshot.syncLabel}"
                )
                val launch = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pending = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, pending)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
