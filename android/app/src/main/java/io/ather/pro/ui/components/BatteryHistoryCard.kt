package io.ather.pro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ather.pro.domain.battery.BatteryHistory
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.TelemetrySample
import io.ather.pro.domain.model.TimeWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

private data class BatteryChartFrame(
    val points: List<TelemetrySample>,
    val startMs: Long,
    val endMs: Long
) {
    fun pointAt(fraction: Float) = BatteryHistory.nearest(points, startMs, endMs, fraction)
    fun fractionAt(timestamp: Long): Float =
        ((timestamp - startMs).toDouble() / (endMs - startMs).coerceAtLeast(1L))
            .toFloat().coerceIn(0f, 1f)
}

private data class BatteryInspection(val frame: BatteryChartFrame, val point: TelemetrySample)

/** One timeline for actual battery measurements, with persistent tap/drag selection. */
@Composable
fun BatteryHistoryCard(dashboard: ScooterDashboardState, modifier: Modifier = Modifier, chargeLimitPercent: Int? = null) {
    val colors = MaterialTheme.colorScheme
    var window by remember { mutableStateOf(TimeWindow.MIN_5) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var inspection by remember { mutableStateOf<BatteryInspection?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val livePoints = remember(dashboard.telemetryHistory, window, nowMs) {
        BatteryHistory.visible(dashboard.telemetryHistory, nowMs, window.durationMs)
    }
    val liveFrame = BatteryChartFrame(
        points = livePoints,
        startMs = window.durationMs?.let { nowMs - it }
            ?: (livePoints.firstOrNull()?.timestamp ?: nowMs - 60_000L),
        endMs = if (window.durationMs == null) {
            (livePoints.lastOrNull()?.timestamp ?: nowMs)
        } else nowMs
    )
    // Freeze both samples and axes while inspecting. A new socket frame cannot move
    // the point under the finger, nor change a value after the rider releases it.
    val frame = inspection?.frame ?: liveFrame
    val latestFrame by rememberUpdatedState(frame)
    val selected = inspection?.point
    val displayed = selected ?: frame.points.lastOrNull()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val readingTimeFormat = remember { SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()) }
    val isLive = dashboard.connection == ConnectionStatus.CONNECTED &&
        dashboard.telemetryHistory.lastOrNull()?.let { nowMs - it.timestamp in 0L..30_000L } == true
    val pointDescription = displayed?.let {
        "${String.format(Locale.US, "%.2f", it.batterySoc)}% at ${readingTimeFormat.format(Date(it.timestamp))}"
    } ?: "No battery readings in this window"

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Battery history", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(
                    if (selected != null) "Selected" else if (isLive) "Live" else "Saved readings",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLive && selected == null) colors.secondary else colors.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeWindow.entries.forEach { option ->
                    FilterChip(
                        selected = window == option,
                        onClick = { window = option; inspection = null },
                        label = { Text(if (option == TimeWindow.TRIP) "All" else option.label) }
                    )
                }
            }
            Text("100% at the top · 0% at the bottom" + (chargeLimitPercent?.let { " · Dashed limit $it%" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text(pointDescription, style = MaterialTheme.typography.bodyMedium,
                color = colors.primary, fontWeight = FontWeight.SemiBold)

            if (frame.points.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Text("Waiting for battery readings. Try a longer time window to see saved data.",
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val low = 0.0
                val high = 100.0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${String.format(Locale.US, "%.1f", low)}% – ${String.format(Locale.US, "%.1f", high)}%",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text("${frame.points.size} readings", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
                Canvas(
                    Modifier.fillMaxWidth().height(160.dp)
                        .semantics {
                            contentDescription = "Battery history chart. $pointDescription"
                            customActions = listOf(-1 to "Previous reading", 1 to "Next reading").map { (step, label) ->
                                CustomAccessibilityAction(label) {
                                    val index = frame.points.indexOfFirst { it.timestamp == displayed?.timestamp }
                                    val next = frame.points[(index + step).coerceIn(0, frame.points.lastIndex)]
                                    inspection = BatteryInspection(frame, next)
                                    true
                                }
                            }
                        }
                        .pointerInput(window) {
                            val inset = 36.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val gestureFrame = latestFrame
                                fun select(x: Float) {
                                    val fraction = (x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)
                                    gestureFrame.pointAt(fraction)?.let {
                                        inspection = BatteryInspection(gestureFrame, it)
                                    }
                                }
                                select(down.position.x)
                                var scrubbing = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed) break
                                    if (!change.pressed) {
                                        if (scrubbing) change.consume()
                                        break
                                    }
                                    val distance = change.position - down.position
                                    // Let vertical motion scroll the page. Claim horizontal
                                    // motion once, then keep selecting even outside the plot.
                                    if (!scrubbing) {
                                        if (abs(distance.y) > viewConfiguration.touchSlop &&
                                            abs(distance.y) > abs(distance.x)) break
                                        scrubbing = abs(distance.x) > viewConfiguration.touchSlop
                                    }
                                    if (scrubbing) {
                                        change.consume()
                                        select(change.position.x)
                                    }
                                }
                            }
                        }
                ) {
                    val inset = 36.dp.toPx()
                    val plotWidth = (size.width - 2 * inset).coerceAtLeast(1f)
                    val topInset = 8.dp.toPx()
                    val plotHeight = (size.height - 2 * topInset).coerceAtLeast(1f)
                    fun position(point: TelemetrySample) = Offset(
                        inset + frame.fractionAt(point.timestamp) * plotWidth,
                        topInset + (1.0 - (point.batterySoc - low) / (high - low).coerceAtLeast(1.0)).toFloat() * plotHeight
                    )
                    for (line in 0..4) {
                        val y = topInset + plotHeight * line / 4f
                        drawContext.canvas.nativeCanvas.drawText("${100 - line * 25}%", 0f, y + 4.dp.toPx(), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colors.onSurfaceVariant.toArgb(); textSize = 10.dp.toPx() })
                        drawLine(colors.outline.copy(alpha = 0.3f), Offset(inset, y),
                            Offset(size.width - inset, y), 1.dp.toPx())
                    }
                    chargeLimitPercent?.let { target ->
                        val y = topInset + (1f - target / 100f) * plotHeight
                        drawLine(colors.tertiary, Offset(inset, y), Offset(size.width - inset, y),
                            strokeWidth = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    }
                    frame.points.zipWithNext().forEach { (first, second) ->
                        if (second.timestamp - first.timestamp <= BatteryHistory.MAX_LINE_GAP_MS) {
                            drawLine(colors.primary, position(first), position(second),
                                strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                    frame.points.forEach { point ->
                        drawCircle(colors.primary, 2.dp.toPx(), position(point))
                    }
                    displayed?.let { point ->
                        val at = position(point)
                        drawLine(colors.onSurface.copy(alpha = 0.5f), Offset(at.x, inset),
                            Offset(at.x, size.height - inset), 1.dp.toPx())
                        drawCircle(colors.surface, 6.dp.toPx(), at)
                        drawCircle(colors.primary, 4.dp.toPx(), at)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeFormat.format(Date(frame.startMs)), style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                    Text(timeFormat.format(Date(frame.endMs)), style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
                val change = frame.points.last().batterySoc - frame.points.first().batterySoc
                if (frame.points.size > 1) {
                    Text("Change: ${String.format(Locale.US, "%+.2f", change)} percentage points",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            // Fixed height avoids moving the graph when the selection first appears.
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected == null) "Touch or drag to see a reading" else "Selection stays until you return to live",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant)
                if (selected != null) {
                    TextButton(onClick = { inspection = null }) { Text("Back to live") }
                }
            }
        }
    }
}
