package io.ather.pro.widget

import android.app.PendingIntent
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.View
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

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        updateAppWidget(context, manager, id)
    }

    companion object {
        private fun renderBatteryGraph(snapshot: DashboardWidgetSnapshot, accent: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(600, 150, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 22f }
            canvas.drawText("100%", 0f, 22f, paint)
            canvas.drawText("0%", 0f, 143f, paint)
            paint.strokeWidth = 1f; paint.color = Color.DKGRAY
            for (index in 0..4) canvas.drawLine(70f, 12f + index * 31, 598f, 12f + index * 31, paint)
            val points = snapshot.batteryHistory.filter { it.soc.isFinite() && it.soc in 0.0..100.0 }.sortedBy { it.timestamp }
            if (points.isEmpty()) {
                paint.color = Color.LTGRAY
                canvas.drawText("Battery history appears after syncing", 80f, 80f, paint)
                return bitmap
            }
            val start = points.first().timestamp
            val duration = (points.last().timestamp - start).coerceAtLeast(1)
            fun x(point: WidgetBatteryPoint) = 70f + ((point.timestamp - start).toDouble() / duration * 528).toFloat()
            fun y(point: WidgetBatteryPoint) = 12f + ((100 - point.soc) / 100 * 124).toFloat()
            snapshot.limitPercent?.let {
                paint.color = Color.GRAY; paint.strokeWidth = 2f; paint.pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
                val y = 12f + (100 - it) / 100f * 124
                canvas.drawLine(70f, y, 598f, y, paint)
                paint.pathEffect = null
            }
            paint.color = accent; paint.strokeWidth = 3f; paint.strokeCap = Paint.Cap.ROUND
            points.zipWithNext().forEach { (a, b) -> if (b.timestamp - a.timestamp <= 120_000) canvas.drawLine(x(a), y(a), x(b), y(b), paint) }
            points.forEach { canvas.drawCircle(x(it), y(it), 2f, paint) }
            return bitmap
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val snapshot = DashboardWidgetSnapshot.load(context)
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val expanded = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 280) >= 260
            val accent = if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent1_200) else Color.rgb(123, 216, 156)
            val views = RemoteViews(context.packageName, R.layout.widget_scooter_status).apply {
                setTextViewText(R.id.widget_soc, snapshot.socText)
                setTextViewText(R.id.widget_range, snapshot.rangeText)
                setTextViewText(R.id.widget_sync, snapshot.syncLabel)
                setTextViewText(R.id.widget_connection, if (System.currentTimeMillis() - snapshot.updatedAtMs > 60_000) "SAVED" else snapshot.connectionLabel)
                setTextViewText(R.id.widget_modes, snapshot.modesText)
                setTextViewText(R.id.widget_charge, snapshot.chargeLabel)
                setViewVisibility(R.id.widget_details, if (expanded) View.VISIBLE else View.GONE)
                setTextColor(R.id.widget_soc, accent)
                setTextColor(R.id.widget_range, accent)
                setTextColor(R.id.widget_connection, accent)
                if (expanded) setImageViewBitmap(R.id.widget_graph, renderBatteryGraph(snapshot, accent))
                setContentDescription(
                    R.id.widget_root,
                    "ScootScribe widget. Battery ${snapshot.socText}, ${snapshot.rangeText}, " +
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
