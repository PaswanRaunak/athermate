package io.ather.pro.widget

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.ather.pro.R
import java.util.Locale
import kotlin.math.roundToInt

/** Material card with a single inset range list. Rendering never touches the account or scooter. */
object WidgetRenderer {
    fun render(context: Context, snapshot: DashboardWidgetSnapshot, widthDp: Float, heightDp: Float, now: Long): RemoteViews {
        val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val height = heightDp / scale
        val roomy = height >= 320
        val details = height >= 205
        val rows = height >= 225 && snapshot.modeRanges.isNotEmpty()
        val accent = context.getColor(R.color.widget_accent)
        val primary = context.getColor(R.color.widget_text)
        val secondary = context.getColor(R.color.widget_secondary)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        val status = when {
            snapshot.updatedAtMs == 0L -> "Open app"
            now - snapshot.updatedAtMs > 60_000 -> "Saved"
            snapshot.connectionLabel == "LIVE" -> if (snapshot.charging) "Charging" else "Live"
            snapshot.connectionLabel == "CONNECTING" -> "Syncing"
            snapshot.connectionLabel == "ERROR" -> "Offline"
            else -> "Offline"
        }
        return RemoteViews(context.packageName, R.layout.widget_scooter_status).apply {
            val outerPadding = dp(if (roomy) 16 else 12)
            setViewPadding(R.id.widget_root, outerPadding, outerPadding, outerPadding, outerPadding)
            setTextViewText(R.id.widget_soc, "${snapshot.socText} battery")
            setTextViewText(R.id.widget_range, "${snapshot.rangeText} · ${snapshot.currentMode ?: "estimated range"}")
            setTextViewTextSize(R.id.widget_soc, TypedValue.COMPLEX_UNIT_SP, if (roomy) 28f else 24f)
            setTextViewTextSize(R.id.widget_range, TypedValue.COMPLEX_UNIT_SP, if (roomy) 14f else 12f)
            val heroPadding = dp(if (roomy) 10 else 3)
            setViewPadding(R.id.widget_hero, 0, heroPadding, 0, heroPadding)
            setTextViewText(R.id.widget_connection, status)
            setTextColor(R.id.widget_connection, if (status == "Live" || status == "Charging") accent else secondary)
            setTextViewText(R.id.widget_sync, if (details) snapshot.syncLabel else "Resize for mode ranges")
            setViewVisibility(R.id.widget_header, if (height >= 155) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_details, if (details) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_footer, if (height >= 130) View.VISIBLE else View.GONE)
            val panelPadding = dp(if (roomy) 12 else 8)
            setViewPadding(R.id.widget_details, panelPadding, panelPadding, panelPadding, panelPadding)
            setViewVisibility(R.id.widget_modes_rows, if (rows) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_modes, if (rows) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_modes, if (snapshot.modeRanges.isEmpty()) snapshot.modesText else
                snapshot.modeRanges.chunked(2).joinToString("\n") { pair ->
                    pair.joinToString("  ·  ") { "${it.name} ${String.format(Locale.getDefault(), "%.0f", it.km)} km" }
                })
            removeAllViews(R.id.widget_modes_rows)
            if (rows) snapshot.modeRanges.take(6).forEach { mode ->
                val row = RemoteViews(context.packageName, R.layout.widget_mode_row).apply {
                    val padding = dp(if (roomy) 4 else 0)
                    setViewPadding(R.id.widget_mode_row, 0, padding, 0, padding)
                    val textSize = if (roomy) 14f else 12f
                    setTextViewTextSize(R.id.widget_mode_name, TypedValue.COMPLEX_UNIT_SP, textSize)
                    setTextViewTextSize(R.id.widget_mode_range, TypedValue.COMPLEX_UNIT_SP, textSize)
                    setTextViewText(R.id.widget_mode_name, mode.name)
                    setTextViewText(R.id.widget_mode_range, String.format(Locale.getDefault(), "%.0f km", mode.km))
                    setViewVisibility(R.id.widget_mode_current, if (mode.active) View.VISIBLE else View.GONE)
                    setTextColor(R.id.widget_mode_name, if (mode.active) accent else primary)
                    setTextColor(R.id.widget_mode_range, if (mode.active) accent else primary)
                    setContentDescription(R.id.widget_mode_row,
                        "${mode.name}${if (mode.active) ", current mode" else ""}, estimated ${mode.km.roundToInt()} kilometres at ${snapshot.socText} battery")
                }
                addView(R.id.widget_modes_rows, row)
            }
            setContentDescription(R.id.widget_root,
                "Athr+. Battery ${snapshot.socText}${if (snapshot.charging) ", charging" else ""}. " +
                    "${snapshot.modesLabel}: ${snapshot.modesText}. ${snapshot.chargeLabel}. $status. ${snapshot.syncLabel}")
        }
    }
}
