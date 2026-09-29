package io.ather.pro.widget

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.ather.pro.R
import java.util.Locale

/** Native RemoteViews; rendering has no account, network, or charge-control side effects. */
object WidgetRenderer {
    fun render(context: Context, snapshot: DashboardWidgetSnapshot, widthDp: Float, heightDp: Float, now: Long): RemoteViews {
        val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val height = heightDp / scale
        val details = height >= 175
        val tiles = height >= 250 && widthDp / scale >= 235 && snapshot.modeRanges.isNotEmpty()
        val accent = context.getColor(R.color.widget_accent)
        val primary = context.getColor(R.color.widget_text)
        val secondary = context.getColor(R.color.widget_secondary)
        val status = when {
            snapshot.updatedAtMs == 0L -> "OPEN APP"
            now - snapshot.updatedAtMs > 60_000 -> "SAVED"
            else -> snapshot.connectionLabel
        }
        return RemoteViews(context.packageName, R.layout.widget_scooter_status).apply {
            setTextViewText(R.id.widget_soc, snapshot.socText)
            setTextViewText(R.id.widget_range, snapshot.rangeText)
            setTextViewText(R.id.widget_current_mode, snapshot.currentMode ?: "Estimated range")
            setTextViewText(R.id.widget_connection, status)
            setTextColor(R.id.widget_connection, if (status == "LIVE") accent else secondary)
            setTextViewText(R.id.widget_modes_label, snapshot.modesLabel + " · est.")
            setTextViewText(R.id.widget_modes, if (snapshot.modeRanges.isEmpty()) snapshot.modesText else
                snapshot.modeRanges.chunked(2).joinToString("\n") { pair ->
                    pair.joinToString("   ·   ") { "${it.name} ${String.format(Locale.getDefault(), "%.0f", it.km)} km" }
                })
            val footer = if (snapshot.charging) "${snapshot.syncLabel} · Charging · ${snapshot.chargeLabel}" else snapshot.syncLabel
            setTextViewText(R.id.widget_sync, if (details) footer else "$footer · Resize for modes")
            setViewVisibility(R.id.widget_header, if (height >= 150) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_details, if (details) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_modes_grid, if (tiles) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_modes, if (tiles) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_sync, if (height >= 130) View.VISIBLE else View.GONE)
            if (height < 185) setTextViewTextSize(R.id.widget_soc, TypedValue.COMPLEX_UNIT_SP, 32f)
            val rows = listOf(R.id.widget_modes_row_first, R.id.widget_modes_row_second)
            rows.forEach { removeAllViews(it) }
            val groups = snapshot.modeRanges.take(6).chunked(3)
            setViewVisibility(rows[1], if (groups.size > 1) View.VISIBLE else View.GONE)
            if (tiles) groups.forEachIndexed { row, modes ->
                repeat(3) { column ->
                    val mode = modes.getOrNull(column)
                    val tile = RemoteViews(context.packageName, R.layout.widget_mode_tile).apply {
                        if (mode == null) {
                            setViewVisibility(R.id.widget_mode_tile, View.INVISIBLE)
                        } else {
                            setTextViewText(R.id.widget_mode_name, mode.name)
                            setTextViewText(R.id.widget_mode_range, String.format(Locale.getDefault(), "%.0f km", mode.km))
                            setInt(R.id.widget_mode_tile, "setBackgroundResource",
                                if (mode.active) R.drawable.widget_mode_active else R.drawable.widget_mode_background)
                            setTextColor(R.id.widget_mode_name, if (mode.active) context.getColor(R.color.widget_on_active) else secondary)
                            setTextColor(R.id.widget_mode_range, if (mode.active) context.getColor(R.color.widget_on_active) else primary)
                            setContentDescription(R.id.widget_mode_tile,
                                "${mode.name}${if (mode.active) ", current mode" else ""}, estimated ${mode.km.toInt()} kilometres at ${snapshot.socText} battery")
                        }
                    }
                    addView(rows[row], tile)
                }
            }
            setContentDescription(R.id.widget_root,
                "ScootScribe. Battery ${snapshot.socText}${if (snapshot.charging) ", charging" else ""}. " +
                    "${snapshot.modesLabel}: ${snapshot.modesText}. ${snapshot.chargeLabel}. $status. ${snapshot.syncLabel}")
        }
    }

}
