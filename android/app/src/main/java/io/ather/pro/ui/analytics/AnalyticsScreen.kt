package io.ather.pro.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TextButton
import io.ather.pro.domain.analytics.AnalyticsTrip
import io.ather.pro.domain.analytics.RideAnalyticsAggregator
import io.ather.pro.domain.analytics.ScorecardState
import io.ather.pro.domain.analytics.VehicleHealthReport
import io.ather.pro.domain.battery.rideEfficiencyKmPerUnit
import io.ather.pro.domain.model.TripRecord
import java.util.Locale
import java.util.TimeZone

/**
 * Self-contained Analytics surface for final integration.
 * Does not wire into MainActivity / AtherDashboardScreen.
 */
@Composable
fun AnalyticsScreen(
    trips: List<TripRecord>,
    scorecard: ScorecardState,
    modifier: Modifier = Modifier,
    timeZone: TimeZone = TimeZone.getDefault(),
    onRefreshScorecard: (() -> Unit)? = null
) {
    val analyticsTrips = remember(trips) { trips.map { AnalyticsTrip.fromTripRecord(it) } }
    val snapshot = remember(analyticsTrips, timeZone) {
        RideAnalyticsAggregator.aggregate(analyticsTrips, timeZone)
    }
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "ANALYTICS",
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = "Observed rides & vehicle scorecard",
            color = colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )

        TotalsCard(
            tripCount = snapshot.totals.tripCount,
            distanceKm = snapshot.totals.totalDistanceKm,
            energyWh = snapshot.totals.totalEnergyWh,
            costInr = snapshot.totals.totalCostInr,
            kmPerUnit = rideEfficiencyKmPerUnit(trips),
            sourceLabel = snapshot.totals.sourceLabel
        )

        ScorecardCard(state = scorecard, onRetry = onRefreshScorecard)

        ScrubChart(series = snapshot.weeklyDistanceKm)
        ScrubChart(series = snapshot.weeklyEnergyWh, accent = colorScheme.secondary)
        ScrubChart(series = snapshot.weeklyCostInr)
        ScrubChart(series = snapshot.weeklyEfficiencyWhPerKm, accent = colorScheme.tertiary)
        ScrubChart(series = snapshot.timeOfDayDistanceKm, accent = colorScheme.secondary)
        ScrubChart(series = snapshot.modeDistanceKm)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TotalsCard(
    tripCount: Int,
    distanceKm: Double,
    energyWh: Double,
    costInr: Double,
    kmPerUnit: Double?,
    sourceLabel: String
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "RIDE TOTALS",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = sourceLabel,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
            if (tripCount == 0) {
                Text(
                    text = "No trips recorded yet",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Trips", tripCount.toString())
                    Metric("Distance", String.format(Locale.US, "%.1f km", distanceKm))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Energy", String.format(Locale.US, "%.0f Wh", energyWh))
                    Metric("Cost", String.format(Locale.US, "₹%.2f", costInr))
                }
                Metric(
                    label = "km/unit",
                    value = kmPerUnit?.let { String.format(Locale.US, "%.2f km/unit", it) }
                        ?: "Unavailable (need distance & energy)"
                )
                Text(
                    text = "km/unit = total km ÷ total kWh (1 unit = 1 kWh). Not Wh/km.",
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }
        }
    }
}

@Composable
fun ScorecardCard(
    state: ScorecardState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "ATHER TRUE HEALTH SCORECARD",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "Vehicle-health report — not BMS SoH",
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
            when (state) {
                ScorecardState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Loading scorecard…", color = colorScheme.onSurfaceVariant)
                    }
                }
                is ScorecardState.Unavailable -> {
                    Text(
                        text = "Scorecard unavailable",
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = state.reason,
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Never treat this card as BMS battery SoH.",
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) {
                            Text("Retry scorecard", color = colorScheme.secondary)
                        }
                    }
                }
                is ScorecardState.Error -> {
                    Text(
                        text = "Scorecard error",
                        color = colorScheme.error,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = state.message,
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) {
                            Text("Retry scorecard", color = colorScheme.secondary)
                        }
                    }
                }
                is ScorecardState.Available -> {
                    ScorecardBody(report = state.report)
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) {
                            Text("Refresh scorecard", color = colorScheme.secondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScorecardBody(report: VehicleHealthReport) {
    val colorScheme = MaterialTheme.colorScheme
    val overall = report.overall
    Text(
        text = overall?.label ?: "Score available",
        color = colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    )
    val scoreText = when {
        overall?.score != null && overall.maxScore != null ->
            String.format(Locale.US, "%.0f / %d", overall.score, overall.maxScore)
        overall?.score != null -> String.format(Locale.US, "%.0f", overall.score)
        else -> "—"
    }
    Text(
        text = scoreText,
        color = colorScheme.primary,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    )
    report.vehicle?.let { vehicle ->
        val bits = listOfNotNull(
            vehicle.name,
            vehicle.registrationMasked,
            vehicle.odoFormatted ?: vehicle.odoKms?.let { "$it km" },
            vehicle.ageYears?.let { "Age $it" }
        )
        if (bits.isNotEmpty()) {
            Text(
                text = bits.joinToString(" · "),
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (report.components.isNotEmpty()) {
        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
        Text(
            text = "COMPONENTS",
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        report.components.forEach { component ->
            val name = component.name ?: component.id ?: "Component"
            val score = component.score?.let { s ->
                component.maxScore?.let { max -> String.format(Locale.US, "%.0f/%d", s, max) }
                    ?: String.format(Locale.US, "%.0f", s)
            } ?: (component.tag?.text ?: "—")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(name, color = colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                Text(score, color = colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (report.wearAndTear.isNotEmpty()) {
        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
        Text(
            text = "WEAR & TEAR",
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        report.wearAndTear.forEach { item ->
            val name = item.name ?: item.id ?: "Item"
            val life = item.lifePercent?.let { "$it% life" } ?: item.tag?.text ?: "—"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(name, color = colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                Text(life, color = colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    report.resale?.let { resale ->
        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
        Text(
            text = "RESALE ESTIMATE",
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = resale.displayText
                ?: listOfNotNull(
                    resale.minValue?.toString(),
                    resale.maxValue?.toString()
                ).joinToString(" – ").takeIf { it.isNotBlank() }?.let { range ->
                    "${resale.currency ?: "INR"} $range"
                }
                ?: "Unavailable",
            color = colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
        resale.disclaimer?.let {
            Text(
                text = it,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }
    }
    report.meta?.lastUpdated?.let {
        Text(
            text = "Updated $it",
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
        )
    }
    Text(
        text = report.sourceLabel,
        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    )
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(Locale.US),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}
