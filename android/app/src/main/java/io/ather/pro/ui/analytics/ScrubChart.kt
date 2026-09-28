package io.ather.pro.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.analytics.ChartSeries
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ScrubChart(
    series: ChartSeries,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val colorScheme = MaterialTheme.colorScheme
    var scrubIndex by remember(series.id, series.points.size) { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = series.title.uppercase(Locale.US),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = series.sourceLabel,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }
            val hud = scrubIndex?.let { series.points.getOrNull(it) }
            if (hud != null) {
                Text(
                    text = "${hud.label}: ${formatValue(hud.value)} ${series.unit}",
                    color = accent,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        if (series.isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No observed data",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            val points = series.points
            val gridColor = colorScheme.outline.copy(alpha = 0.25f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(points) {
                        detectTapGestures { offset ->
                            scrubIndex = indexForX(offset.x, size.width.toFloat(), points.size)
                        }
                    }
                    .pointerInput(points) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                scrubIndex = indexForX(offset.x, size.width.toFloat(), points.size)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                scrubIndex = indexForX(change.position.x, size.width.toFloat(), points.size)
                            }
                        )
                    }
            ) {
                val maxVal = points.maxOf { it.value }.coerceAtLeast(1.0)
                val barCount = points.size
                val gap = 4.dp.toPx()
                val barWidth = ((size.width - gap * (barCount - 1).coerceAtLeast(0)) / barCount)
                    .coerceAtLeast(2.dp.toPx())

                listOf(0.25f, 0.5f, 0.75f).forEach { ratio ->
                    val y = size.height * (1f - ratio)
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }

                if (barCount <= 24) {
                    points.forEachIndexed { i, point ->
                        val x = i * (barWidth + gap)
                        val h = ((point.value / maxVal) * size.height).toFloat().coerceAtLeast(2.dp.toPx())
                        val selected = scrubIndex == i
                        drawRect(
                            color = if (selected) accent else accent.copy(alpha = 0.55f),
                            topLeft = Offset(x, size.height - h),
                            size = Size(barWidth, h)
                        )
                    }
                } else {
                    val path = Path()
                    points.forEachIndexed { i, point ->
                        val x = if (barCount == 1) 0f else i.toFloat() / (barCount - 1) * size.width
                        val y = size.height - ((point.value / maxVal) * size.height).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = accent,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    scrubIndex?.let { idx ->
                        val point = points[idx]
                        val x = if (barCount == 1) 0f else idx.toFloat() / (barCount - 1) * size.width
                        val y = size.height - ((point.value / maxVal) * size.height).toFloat()
                        drawLine(accent.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                        drawCircle(accent, radius = 5.dp.toPx(), center = Offset(x, y))
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Touch or drag to scrub",
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }
    }
}

private fun indexForX(x: Float, width: Float, count: Int): Int {
    if (count <= 0) return 0
    if (count == 1) return 0
    val t = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (t * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

private fun formatValue(value: Double): String =
    if (value >= 100.0) String.format(Locale.US, "%.0f", value)
    else String.format(Locale.US, "%.1f", value)
