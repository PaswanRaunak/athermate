package io.ather.pro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.charging.ChargingSessionHistory
import io.ather.pro.domain.model.ChargingSessionRecord
import io.ather.pro.ui.components.number
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val COMPACT_SESSION_COUNT = 6

/**
 * Completed charging sessions with energy and rupee cost, summarised per calendar
 * month. Costs are the tariff recorded when each session ended, not today's tariff.
 */
@Composable
fun ChargingHistoryCard(
    sessions: List<ChargingSessionRecord>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val monthBuckets = remember(sessions) { ChargingSessionHistory.monthlyBuckets(sessions) }
    val thisMonth = remember(sessions) {
        ChargingSessionHistory.bucketFor(sessions, YearMonth.now())
    }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("CHARGING HISTORY", style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, letterSpacing = 1.4.sp)
                if (sessions.isNotEmpty()) {
                    TextButton(onClick = { showClearDialog = true }) { Text("Clear") }
                }
            }

            if (sessions.isEmpty()) {
                Text("Every completed charge lands here — start and end battery %, duration, energy and cost at your tariff.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            } else {
                thisMonth?.let { month ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column {
                            Text("THIS MONTH", style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant, letterSpacing = 1.sp)
                            Text("₹${number(month.costInr, 2)}", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold, color = colors.primary)
                        }
                        Column(Modifier.align(Alignment.Bottom)) {
                            Text("${number(month.energyKWh, 2)} kWh", style = MaterialTheme.typography.titleMedium)
                            if (month.hasUnknownEnergy) Text("some sessions unmeasured",
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Column(Modifier.align(Alignment.Bottom)) {
                            Text("${month.sessionCount} ${if (month.sessionCount == 1) "charge" else "charges"}",
                                style = MaterialTheme.typography.titleMedium)
                            Text(formatDuration(month.chargeMinutes),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }

                val visible = if (expanded) sessions else sessions.take(COMPACT_SESSION_COUNT)
                val groups = remember(visible) {
                    // YearMonth.from(Instant) throws (Unsupported field: Year) — an
                    // Instant must be zoned first. This crashed the history card.
                    visible.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAtMs).atZone(ZoneId.systemDefault())) }
                }
                groups.forEach { (month, list) ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val bucket = monthBuckets.firstOrNull { it.month == month }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom) {
                            Text(monthLabel(month), style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            bucket?.let {
                                Text("₹${number(it.costInr, 2)} · ${number(it.energyKWh, 1)} kWh",
                                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            }
                        }
                        list.forEach { session -> SessionRow(session) }
                    }
                    if (month != groups.keys.last()) HorizontalDivider(color = colors.outlineVariant)
                }

                if (sessions.size > COMPACT_SESSION_COUNT) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (expanded) "Show less"
                        else "Show all ${sessions.size} sessions")
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear charging history?") },
            text = { Text("All recorded sessions and their costs will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; onClear() }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Keep") }
            }
        )
    }
}

@Composable
private fun SessionRow(session: ChargingSessionRecord) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(sessionDate(session.startedAtMs), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val socs = listOfNotNull(
                session.startSoc?.takeIf { it.isFinite() }?.let { "${it.roundToInt()}%" },
                session.endSoc?.takeIf { it.isFinite() }?.let { "${it.roundToInt()}%" }
            )
            val socText = if (socs.size == 2) "${socs[0]} → ${socs[1]}" else "—"
            Text("$socText · ${formatDuration((session.endedAtMs - session.startedAtMs) / 60_000)}",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(session.costInr?.let { "₹${number(it, 2)}" } ?: "—",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(session.energyKWh?.let { "${number(it, 2)} kWh" } ?: "energy n/a",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun monthLabel(month: YearMonth): String =
    month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))

private fun sessionDate(epochMs: Long): String =
    SimpleDateFormat("EEE, d MMM · h:mm a", Locale.getDefault()).format(Date(epochMs))

private fun formatDuration(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}h ${mins.toString().padStart(2, '0')}m" else "${mins}m"
}
