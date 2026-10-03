package io.ather.pro.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.range.RangeEstimator
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal fun number(value: Double?, decimals: Int = 0): String =
    value?.takeIf(Double::isFinite)?.let { String.format(Locale.getDefault(), "%.${decimals}f", it) } ?: "—"

@Composable
fun FreshnessLabel(state: ScooterDashboardState, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.lastUpdated) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val readingAt = state.batteryReportedAt ?: state.telemetry?.sourceTimestampMs ?: state.batteryUpdatedAt ?: state.lastUpdated
    val age = readingAt?.let { ((now - it).coerceAtLeast(0) / 1000) }
    val fresh = state.connection == ConnectionStatus.CONNECTED && age != null && age < 60
    val text = when {
        fresh -> "Live · Updated ${age}s ago"
        age != null -> "Last received ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(readingAt!!))} · ${if (state.connection == ConnectionStatus.CONNECTED) "Waiting for scooter" else "Reconnecting"}"
        state.connection == ConnectionStatus.CONNECTING -> "Connecting to your scooter…"
        else -> "Waiting for scooter data · Tap refresh to retry"
    }
    Text(text, modifier, style = MaterialTheme.typography.bodySmall,
        color = if (fresh) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun EnergySummaryCard(state: ScooterDashboardState) {
    val soc = state.telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }
    val range = RangeEstimator.current(state.telemetry, state.settings.selectedModel)
    val charging = io.ather.pro.domain.charging.ChargingControl.isActivelyCharging(state.telemetry)
    val green = androidx.compose.ui.graphics.Color(0xFF00E89D)
    val shape = RoundedCornerShape(28.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()
        .clip(shape)
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(
            androidx.compose.ui.graphics.Color(0xFF233038), androidx.compose.ui.graphics.Color(0xFF10191D))))
        .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f), shape)) {
        val wide = maxWidth >= 540.dp
        val batteryTextStyle = if (maxWidth < 350.dp) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.vehicleProfile?.displayName ?: state.settings.selectedModel.displayName.uppercase(),
                color = androidx.compose.ui.graphics.Color(0xFFBBC8CD),
                style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(if (wide) 0.45f else 0.55f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = if (charging) green.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(
                            1.dp, if (charging) green.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline)) {
                        Text(if (charging) "ϟ  Charging" else state.telemetry?.chargingStatus ?: "Scooter status",
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (charging) green else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${number(soc)}%", style = batteryTextStyle,
                        fontWeight = FontWeight.Black, maxLines = 1,
                        color = androidx.compose.ui.graphics.Color.White)
                    Text("${number(range)} km range", style = MaterialTheme.typography.titleMedium,
                        color = androidx.compose.ui.graphics.Color.White)
                }
                Box(Modifier.weight(if (wide) 0.55f else 0.45f).height(if (wide) 220.dp else 170.dp),
                    contentAlignment = Alignment.Center) {
                    if (charging) Box(Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.radialGradient(listOf(
                            green.copy(alpha = 0.18f), androidx.compose.ui.graphics.Color.Transparent))))
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(io.ather.pro.R.drawable.scooter_hero),
                        contentDescription = null, modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                }
            }
            LinearProgressIndicator(progress = { ((soc ?: 0.0) / 100).toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                color = green, trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(soc?.let { "${number(it, 2)}% measured" } ?: "Waiting for battery reading",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
                state.telemetry?.mode?.let { Text(it, color = green,
                    style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
fun ModeRangeCard(state: ScooterDashboardState) {
    val ranges = RangeEstimator.modes(state.telemetry, state.settings.selectedModel)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val soc = state.telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }
            Text(soc?.let { "Range at ${number(it)}% battery" } ?: "Range by ride mode",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (ranges.isEmpty()) Text("Mode ranges appear when reported by your scooter.", style = MaterialTheme.typography.bodySmall)
            ranges.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { mode ->
                        Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                            color = if (mode.active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(12.dp)) {
                                Text(mode.name + if (mode.active) " · Current" else "", style = MaterialTheme.typography.labelMedium)
                                Text("${number(mode.km)} km", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("Estimated remaining kilometres. Changes with riding conditions.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ChargeEstimateCard(state: ScooterDashboardState, target: Int) {
    val estimate = RangeEstimator.target(state.telemetry, target,
        state.settings.selectedModel.usableCapacityWh, state.settings.tariffRatePerKWh, state.settings.selectedModel)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Charge to $target%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (estimate == null) {
                Text("Waiting for a battery reading to estimate your charge.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val soc = state.telemetry?.batterySoc ?: 0.0
                Text("${number(soc)}% reported · ${number(estimate.remainingPercent)}% to go", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { (soc / target.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    EstimateValue("Energy", "${number(estimate.energyKWh, 2)} kWh")
                    EstimateValue("Cost", "₹${number(estimate.costInr, 1)}")
                    EstimateValue("Range at target", "${number(estimate.rangeAtTargetKm)} km")
                }
                if (state.telemetry?.charging == true && estimate.minutesToTarget != null) {
                    Text("About ${estimate.minutesToTarget.roundToInt()} min to target", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Estimates use your scooter’s range and selected battery size. Cost excludes charging losses.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EstimateValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
