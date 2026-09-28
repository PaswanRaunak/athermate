package io.ather.pro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.ather.pro.domain.battery.*
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.TimeWindow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

private data class RideFrame(val points: List<ChartPoint>, val start: Long, val end: Long)

@Composable
fun RideGraphsCard(dashboard: ScooterDashboardState) {
    val colors = MaterialTheme.colorScheme
    var metric by rememberSaveable { mutableStateOf(RideMetric.SPEED) }
    var window by rememberSaveable { mutableStateOf(TimeWindow.MIN_15) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var frozen by remember { mutableStateOf<RideFrame?>(null) }
    var selected by remember { mutableStateOf<ChartPoint?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
    val start = window.durationMs?.let { now - it } ?: dashboard.rideHistory.firstOrNull()?.timestamp ?: now
    val points = remember(dashboard.rideHistory, metric, start, now) { RideHistory.series(dashboard.rideHistory, metric, start, now) }
    val frame = frozen ?: RideFrame(points, start, now)
    val currentFrame by rememberUpdatedState(frame)
    val displayed = selected ?: frame.points.lastOrNull()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ride graphs", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RideMetric.entries.forEach { option ->
                    FilterChip(selected = metric == option, onClick = { metric = option; frozen = null; selected = null }, label = { Text(option.label) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeWindow.entries.forEach { option ->
                    FilterChip(selected = window == option, onClick = { window = option; frozen = null; selected = null }, label = { Text(if (option == TimeWindow.TRIP) "All" else option.label) })
                }
            }
            Text("${number(displayed?.value, 1)} ${metric.unit}" + (displayed?.let { " · ${timeFormat.format(Date(it.timestamp))}" } ?: ""),
                style = MaterialTheme.typography.titleLarge, color = colors.primary)
            if (frame.points.isEmpty()) {
                Box(Modifier.height(160.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No ${metric.label.lowercase()} readings in this window. Recorded scooter data appears here as it arrives.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val high = (ceil(frame.points.maxOf { it.value } / 10) * 10).coerceAtLeast(if (metric == RideMetric.DISTANCE) 1.0 else 10.0)
                val selectAt: (Float, Float) -> Unit = { x, width ->
                    val fixed = currentFrame
                    val target = fixed.start + ((x / width.coerceAtLeast(1f)).coerceIn(0f, 1f) * (fixed.end - fixed.start)).toLong()
                    selected = fixed.points.minByOrNull { abs(it.timestamp - target) }
                    frozen = fixed
                }
                Text("0 – ${number(high)} ${metric.unit} · ${frame.points.size} readings", style = MaterialTheme.typography.labelSmall)
                Canvas(Modifier.fillMaxWidth().height(170.dp)
                    .semantics { contentDescription = "${metric.label} graph, zero to $high ${metric.unit}. Current reading ${displayed?.value ?: "unavailable"}" }
                    .pointerInput(metric, window) { detectTapGestures { selectAt(it.x, size.width.toFloat()) } }
                    .pointerInput(metric, window) { detectHorizontalDragGestures { change, _ -> change.consume(); selectAt(change.position.x, size.width.toFloat()) } }) {
                    val inset = 6.dp.toPx()
                    fun position(point: ChartPoint) = Offset(
                        ((point.timestamp - frame.start).toDouble() / (frame.end - frame.start).coerceAtLeast(1) * size.width).toFloat(),
                        inset + (1 - point.value / high).toFloat() * (size.height - inset * 2))
                    for (line in 0..4) {
                        val y = inset + (size.height - 2 * inset) * line / 4
                        drawLine(colors.outline.copy(alpha = .3f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    frame.points.zipWithNext().forEach { (a, b) ->
                        if (b.timestamp - a.timestamp <= BatteryHistory.MAX_LINE_GAP_MS) drawLine(colors.primary, position(a), position(b), 2.5.dp.toPx(), StrokeCap.Round)
                    }
                    frame.points.forEach { drawCircle(colors.primary, 2.dp.toPx(), position(it)) }
                    displayed?.let {
                        val at = position(it)
                        drawLine(colors.onSurface.copy(alpha = .5f), Offset(at.x, 0f), Offset(at.x, size.height), 1.dp.toPx())
                        drawCircle(colors.secondary, 5.dp.toPx(), at)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeFormat.format(Date(frame.start)), style = MaterialTheme.typography.labelSmall)
                    Text(timeFormat.format(Date(frame.end)), style = MaterialTheme.typography.labelSmall)
                }
                if (metric == RideMetric.SPEED) Text("Peak ${number(frame.points.maxOf { it.value }, 1)} km/h in this window", style = MaterialTheme.typography.bodySmall)
                if (metric == RideMetric.DISTANCE) Text("Distance is the odometer change from the first reading in this window.", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tap or drag to inspect · Gaps mean missing data", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                if (frozen != null) TextButton(onClick = { frozen = null; selected = null }) { Text("Live") }
            }
        }
    }
}
