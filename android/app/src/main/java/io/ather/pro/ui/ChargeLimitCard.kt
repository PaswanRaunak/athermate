package io.ather.pro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargeTimeEstimator
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.ui.components.number
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun ChargeLimitCard(
    snapshot: ChargeLimitController.Snapshot,
    dashboard: ScooterDashboardState,
    onEnabledChange: (Boolean) -> Unit,
    onPercentChange: (Int, Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by rememberSaveable(snapshot.percent) { mutableIntStateOf(snapshot.percent) }
    var power by rememberSaveable(snapshot.chargerPowerW) { mutableIntStateOf(snapshot.chargerPowerW) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val active = ChargingControl.isActivelyCharging(dashboard.telemetry)
    val reportedAt = dashboard.batteryReportedAt ?: dashboard.batteryUpdatedAt
    val unapplied = selected != snapshot.percent || power != snapshot.chargerPowerW
    val estimate = if (snapshot.enabled && !unapplied && active && snapshot.estimate != null)
        snapshot.estimate else ChargeTimeEstimator.estimate(dashboard.telemetry, selected,
            dashboard.settings.selectedModel.usableCapacityWh, power, reportedAt ?: 0L, now,
            dashboard.chargingRatePercentPerMinute, active)
    val clockFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    fun at(time: Long) = clockFormat.format(Date(time))
    fun day(time: Long) = dayFormat.format(Date(time)).let { if (it == dayFormat.format(Date(now))) "" else "$it ·" }
    fun remaining(until: Long): String {
        val minutes = ceil((until - now).coerceAtLeast(0L) / 60_000.0).toInt()
        return if (minutes == 0) "now" else if (minutes < 60) "about $minutes min" else
            "about ${minutes / 60} h ${minutes % 60} min"
    }
    val pending = snapshot.status == ChargeLimitController.Status.PENDING
    val colors = MaterialTheme.colorScheme
    val (statusLabel, statusColor) = when (snapshot.status) {
        ChargeLimitController.Status.DISABLED -> "Off" to colors.onSurfaceVariant
        ChargeLimitController.Status.MONITORING -> "Monitoring" to colors.primary
        ChargeLimitController.Status.PENDING -> "Waiting for scooter confirmation" to colors.tertiary
        ChargeLimitController.Status.CONFIRMED -> "Stop confirmed" to colors.primary
        ChargeLimitController.Status.ERROR -> "Needs attention" to colors.error
    }
    val soc = dashboard.telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }

    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // header: title + live status
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Automatic charge limit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                        Text(if (snapshot.enabled) "$statusLabel · ${snapshot.percent}%" else statusLabel,
                            style = MaterialTheme.typography.labelMedium, color = statusColor)
                    }
                }
                if (snapshot.enabled) TextButton(onClick = { onEnabledChange(false) }) { Text("Turn off") }
            }

            // hero: big target with current battery context
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$selected", fontSize = 56.sp, lineHeight = 56.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp,
                        color = if (snapshot.enabled) colors.primary else colors.onSurface)
                    Text("%", Modifier.padding(start = 3.dp, bottom = 8.dp),
                        fontSize = 22.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (soc != null) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${number(soc)}% now", style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface)
                        val toGo = (selected - soc).coerceAtLeast(0.0)
                        Text(if (toGo <= 0.0) "target reached" else "${number(toGo)}% to go",
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
            Slider(value = selected.toFloat(), onValueChange = { selected = it.roundToInt() },
                valueRange = ChargeLimitController.MIN_PERCENT.toFloat()..ChargeLimitController.MAX_PERCENT.toFloat(),
                steps = ChargeLimitController.MAX_PERCENT - ChargeLimitController.MIN_PERCENT - 1,
                modifier = Modifier.semantics { contentDescription = "Charge target percent" })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 70, 80, 90, 100).forEach { target ->
                    FilterChip(selected = selected == target, onClick = { selected = target }, label = { Text("$target%") })
                }
            }

            // charger power
            Text("CHARGER POWER", style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, letterSpacing = 1.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChargeTimeEstimator.CHARGER_POWERS.forEach { watts ->
                    FilterChip(selected = power == watts, onClick = { power = watts },
                        label = { Text("$watts W") })
                }
            }

            // ETA panel
            Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (estimate != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("REACHING $selected%", style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant, letterSpacing = 1.sp)
                                Text("${day(estimate.targetAtMs)} ${at(estimate.targetAtMs)}",
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("PAUSE", style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant, letterSpacing = 1.sp)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = colors.tertiary)
                                    Text(at(estimate.stopAtMs), style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold, color = colors.tertiary)
                                }
                            }
                        }
                        Text("${remaining(estimate.targetAtMs)} to target · ${remaining(estimate.stopAtMs)} to pause · ${estimate.basisLabel}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        val timerArmed = snapshot.enabled && !unapplied && snapshot.armed && snapshot.estimate != null &&
                            snapshot.status == ChargeLimitController.Status.MONITORING
                        Text(if (timerArmed) "Timer armed — pause is scheduled" else
                            if (pending) "Stop requested — waiting for confirmation" else
                            if (!snapshot.enabled || unapplied) "Preview — apply the limit to arm it" else "Timer not armed",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (timerArmed) colors.primary else colors.onSurfaceVariant)
                        if (!active) Text("If charging starts now. The timer waits for reported charging.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        else if (snapshot.enabled && !unapplied && snapshot.estimate == null) {
                            Text("Waiting for a newer charging reading to arm the timer.",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        if (active && reportedAt != null && now - reportedAt > 30_000L) {
                            val age = ceil((now - reportedAt).coerceAtLeast(0L) / 60_000.0).toInt()
                            Text("Based on a $age min old battery reading; assumes charging continued.",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Text("Approximate. Fallback stops slightly early; final charge may differ from $selected%.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else {
                        Text("Waiting for a timestamped battery reading to estimate $selected%.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Button(onClick = { onPercentChange(selected, power) }, modifier = Modifier.fillMaxWidth(),
                enabled = !pending && (!snapshot.enabled || unapplied)) {
                Text(if (snapshot.enabled) "Apply $selected% limit" else "Enable $selected% limit")
            }
            if (snapshot.enabled && unapplied) Text("New target or charger setting has not been applied.",
                style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
            snapshot.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (snapshot.status == ChargeLimitController.Status.ERROR) {
                OutlinedButton(onClick = onRetry) { Text("Retry stop at ${snapshot.percent}%") }
            }
            Text("Checks every 5 seconds. Sends Pause when a fresh reading reaches your target or the estimated fallback time arrives. Keep this phone online; turn the limit off to end monitoring.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (snapshot.enabled) Text("Turn the limit off before resuming a charge above ${snapshot.percent}%.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
