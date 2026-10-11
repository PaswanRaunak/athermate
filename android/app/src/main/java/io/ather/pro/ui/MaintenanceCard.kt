package io.ather.pro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.monitoring.MaintenancePolicy
import io.ather.pro.ui.components.number
import io.ather.pro.util.MaintenanceCenter
import io.ather.pro.util.MaintenanceSnapshot
import java.time.YearMonth

/**
 * Service schedule card: next odometer service (every 3,000 km) and monthly tyre
 * check, each with a one-tap "mark done" that reschedules from the current reading.
 */
@Composable
fun MaintenanceCard(dashboard: ScooterDashboardState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val center = remember { MaintenanceCenter.getInstance(context) }
    val snapshot by center.snapshot.collectAsStateWithLifecycle()
    val odometerKm = dashboard.telemetry?.odoKm?.takeIf { it.isFinite() && it > 0.0 }
    val colors = MaterialTheme.colorScheme

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SERVICE SCHEDULE", style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, letterSpacing = 1.4.sp)

            // Service row
            when {
                !snapshot.serviceInitialised && odometerKm == null ->
                    Text("Next service is scheduled once your scooter reports an odometer reading.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                !snapshot.serviceInitialised ->
                    Text("Scheduling next service from ${number(odometerKm, 0)} km…",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                else -> {
                    val next = snapshot.nextDueOdoKm
                    val remaining = snapshot.kmRemaining
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Next service", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    remaining == null -> next?.let { "at ${number(it, 0)} km" } ?: "—"
                                    remaining <= 0 -> "overdue by ${number(-remaining, 0)} km"
                                    else -> "in ${number(remaining, 0)} km · at ${number(next!!, 0)} km"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (remaining != null && remaining <= 0) colors.error
                                else if (remaining != null && remaining <= MaintenancePolicy.SERVICE_EARLY_WARNING_KM)
                                    colors.tertiary else colors.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { center.markServiced(odometerKm) },
                            enabled = odometerKm != null) { Text("Mark serviced") }
                    }
                }
            }

            HorizontalDivider(color = colors.outlineVariant)

            // Tyre check row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tyre check", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        snapshot.lastTyreCheckEpochMonth?.let { "last checked ${monthLabel(it)}" }
                            ?: "not checked yet — monthly reminder",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { center.markTyreChecked() }) { Text("Mark checked") }
            }

            Text("Reminders follow the generic ${MaintenancePolicy.SERVICE_INTERVAL_KM.toInt()} km interval and a calendar-month tyre check, not Ather's official service schedule for your variant.",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun monthLabel(epochMonth: Long): String =
    YearMonth.now().with(java.time.temporal.ChronoField.PROLEPTIC_MONTH, epochMonth)
        .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.getDefault()))
