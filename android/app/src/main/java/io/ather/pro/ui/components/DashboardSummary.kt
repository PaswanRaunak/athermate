package io.ather.pro.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.BatteryStd
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ScooterArtwork
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.range.RangeEstimator
import io.ather.pro.ui.visuals.ChargingGlow
import io.ather.pro.ui.visuals.cardOutline
import io.ather.pro.ui.visuals.chargingEdge
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(
            if (fresh) ChargingGlow else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = if (fresh) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EnergySummaryCard(state: ScooterDashboardState, limit: ChargeLimitController.Snapshot, accountName: String) {
    val context = LocalContext.current
    val drawing = ScooterArtwork.drawing(state.modelForRange, state.settings.artworkColour ?: state.vehicleProfile?.colour)
    val drawingId = drawing?.let { context.resources.getIdentifier(it.drawable, "drawable", context.packageName) } ?: 0
    val supplied = drawing?.takeIf { drawingId != 0 }
    val soc = state.telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }
    val range = RangeEstimator.current(state.telemetry, state.modelForRange)
    val charging = ChargingControl.isActivelyCharging(state.telemetry)
    val plugged = ChargingControl.isPluggedIn(state.telemetry)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val readingAt = state.batteryReportedAt ?: state.telemetry?.sourceTimestampMs ?: state.batteryUpdatedAt
    val fresh = state.connection == ConnectionStatus.CONNECTED && readingAt != null &&
        now - readingAt in 0L..120_000L
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val muted = colors.onSurfaceVariant
    val chargeAccent = if (colors.surface.luminance() > 0.5f) Color(0xFF16A34A) else ChargingGlow
    val status = when {
        charging && fresh -> "Charging"
        charging -> "Charging · saved"
        ChargingControl.isPaused(state.telemetry) -> "Paused"
        plugged -> "Charger connected"
        soc == null -> "Waiting for data"
        else -> friendlyStatus(state.telemetry?.chargingStatus) ?: "Ready to ride"
    }
    val shape = RoundedCornerShape(28.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()
        .clip(shape)
        .background(Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainer, colors.surface)))
        .cardOutline(shape, colors.onSurface.copy(alpha = 0.4f))
        .chargingEdge(fresh && plugged, shape, chargeAccent, flowing = charging)) {
        if (charging && fresh) {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                listOf(chargeAccent.copy(alpha = 0.10f), Color.Transparent))))
        }
        val wide = maxWidth >= 540.dp
        val narrow = maxWidth < 340.dp
        Column(Modifier.padding(if (narrow) 18.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (supplied != null) {
                        Text(accountName, color = colors.onSurface, style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (state.vehicleProfile != null)
                            "${state.modelForRange.displayName} · ${supplied.colourLabel}"
                            else "Confirming vehicle details…",
                            color = muted, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("YOUR ENERGY", color = muted, style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.8.sp)
                        Text(state.vehicleProfile?.displayName ?: "My scooter",
                            color = colors.onSurface, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
                    Icon(Icons.Outlined.BatteryStd, null, Modifier.padding(10.dp).size(18.dp), tint = accent)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = if (fresh && plugged) chargeAccent.copy(alpha = 0.1f) else colors.surfaceContainerHigh,
                        shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (charging) Icon(Icons.Default.Bolt, null, Modifier.size(14.dp),
                                tint = if (fresh) chargeAccent else muted)
                            else if (plugged) Icon(Icons.Filled.ElectricalServices, null, Modifier.size(14.dp),
                                tint = if (fresh) chargeAccent else muted)
                            Text(status, style = MaterialTheme.typography.labelMedium,
                                color = if (fresh && plugged) chargeAccent else muted,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(number(soc), color = colors.onSurface, fontSize = if (narrow) 52.sp else 64.sp,
                            lineHeight = if (narrow) 56.sp else 68.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp, maxLines = 1)
                        if (soc != null) Text("%", Modifier.padding(start = 2.dp, bottom = 7.dp),
                            fontSize = 26.sp, color = muted, fontWeight = FontWeight.Medium)
                    }
                    soc?.let { level ->
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(colors.outlineVariant)) {
                            Box(Modifier.fillMaxWidth(fraction = (level / 100.0).toFloat()).fillMaxHeight()
                                .clip(RoundedCornerShape(50)).background(if (plugged) chargeAccent else accent))
                        }
                    }
                    Text(soc?.let { "${number(it, 2)}% reported" } ?: "Battery unavailable",
                        color = muted, style = MaterialTheme.typography.bodySmall)
                    if (charging && fresh) {
                        state.telemetry?.timeToFullChargeMin?.takeIf { it > 0.0 }?.let { minutes ->
                            Text("Full in about ${minutes.roundToInt()} min",
                                color = chargeAccent, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (supplied != null) {
                    Image(painter = painterResource(drawingId),
                        contentDescription = "${state.modelForRange.displayName}, ${supplied.colourLabel}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(if (wide) 220.dp else if (narrow) 132.dp else 176.dp,
                            if (wide) 124.dp else if (narrow) 76.dp else 100.dp))
                } else {
                    EnergyBatteryVisual(soc, charging, fresh, limit.percent.takeIf { limit.enabled },
                        Modifier.size(if (wide) 208.dp else if (narrow) 128.dp else 156.dp))
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HeroStat("RANGE", Modifier.weight(1.1f)) {
                    Text("${number(range)} km", color = colors.onSurface,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                }
                HeroStat("ODOMETER", Modifier.weight(1f)) {
                    val odo = state.telemetry?.odoKm?.takeIf { it > 0.0 }
                    Text(if (odo != null) "${number(odo, 0)} km" else "—", color = colors.onSurface,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                HeroStat("CHARGE LIMIT", Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (limit.enabled) Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(colors.tertiary))
                        Text(if (limit.enabled) "${limit.percent}%" else "Off",
                            color = if (limit.enabled) colors.tertiary else muted,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (limit.enabled && limit.armed && limit.status == ChargeLimitController.Status.MONITORING) {
                limit.estimate?.let { estimate ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = muted)
                        Text("Est. pause ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(estimate.stopAtMs))} · Approximate",
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
    }
}

@Composable
fun ModeRangeCard(state: ScooterDashboardState) {
    val ranges = RangeEstimator.modes(state.telemetry, state.modelForRange)
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val soc = state.telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }
            Text(soc?.let { "Range at ${number(it)}% battery" } ?: "Range by ride mode",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (ranges.isEmpty()) Text("Mode ranges appear when reported by your scooter.", style = MaterialTheme.typography.bodySmall)
            ranges.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { mode ->
                        ModeTile(mode, Modifier.weight(1f))
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
private fun ModeTile(mode: io.ather.pro.domain.range.RideModeRange, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val active = mode.active
    Surface(modifier, shape = RoundedCornerShape(16.dp),
        color = if (active) colors.primaryContainer else colors.surfaceContainerHigh,
        border = if (active) androidx.compose.foundation.BorderStroke(1.5.dp, colors.primary) else null) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (active) Icon(Icons.Default.Bolt, null, Modifier.size(13.dp), tint = colors.primary)
                Text(mode.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(number(mode.km), color = colors.onSurface, style = MaterialTheme.typography.titleLarge)
                Text(" km", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
            }
            if (active) Text("CURRENT", style = MaterialTheme.typography.labelSmall,
                color = colors.primary, letterSpacing = 1.2.sp)
        }
    }
}

@Composable
private fun HeroStat(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
        content()
    }
}

/** Vehicle-reported charging states, worded for humans. */
private fun friendlyStatus(raw: String?): String? = when (raw?.trim()?.lowercase()) {
    null, "" -> null
    "initializing", "initialising", "init", "preparing" -> "Charge starting"
    "awaiting", "waiting" -> "Waiting to charge"
    "abnormal", "fault", "error" -> "Check charger"
    "completed", "complete" -> "Charge complete"
    else -> raw.replaceFirstChar { it.uppercase() }
}

@Composable
fun ChargeEstimateCard(state: ScooterDashboardState, target: Int) {
    val estimate = RangeEstimator.target(state.telemetry, target,
        state.settings.selectedModel.usableCapacityWh, state.settings.tariffRatePerKWh, state.modelForRange)
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (estimate == null) {
                Text("Charge to $target%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Waiting for a battery reading to estimate your charge.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val soc = state.telemetry?.batterySoc ?: 0.0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("CHARGE TO $target%", style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant, letterSpacing = 1.sp)
                        Text("${number(soc)}% reported", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${number(estimate.remainingPercent)}%", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold, color = colors.primary)
                        Text("to go", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
                LinearProgressIndicator(progress = { (soc / target.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                    trackColor = colors.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    EstimateValue("Energy", "${number(estimate.energyKWh, 2)} kWh")
                    EstimateValue("Cost", "₹${number(estimate.costInr, 1)}")
                    EstimateValue("Range at target", "${number(estimate.rangeAtTargetKm)} km")
                }
                if (state.telemetry?.charging == true && estimate.minutesToTarget != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = colors.primary)
                        Text("About ${estimate.minutesToTarget.roundToInt()} min to target",
                            style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
                Text("Estimates use your scooter’s range and selected battery size. Cost excludes charging losses.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
