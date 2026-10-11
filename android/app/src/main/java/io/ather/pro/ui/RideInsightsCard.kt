package io.ather.pro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.analytics.RideInsights
import io.ather.pro.domain.model.TripRecord
import io.ather.pro.ui.components.number
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Weekly / monthly riding summary over stored trips: distance, rupees saved against
 * the petrol a commuter bike would burn, and the most efficient ride on record.
 */
@Composable
fun RideInsightsCard(
    trips: List<TripRecord>,
    petrolPricePerLitre: Double,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var windowName by rememberSaveable { mutableStateOf(RideInsights.Window.WEEK.name) }
    val window = remember(windowName) {
        runCatching { RideInsights.Window.valueOf(windowName) }.getOrDefault(RideInsights.Window.WEEK)
    }
    val summary = remember(trips, window, petrolPricePerLitre) {
        RideInsights.summarize(
            trips = trips,
            window = window,
            nowMs = System.currentTimeMillis(),
            petrolPricePerLitre = petrolPricePerLitre
        )
    }
    val windowLabel = if (window == RideInsights.Window.WEEK) "week" else "month"

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("RIDE INSIGHTS", style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, letterSpacing = 1.4.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = window == RideInsights.Window.WEEK,
                        onClick = { windowName = RideInsights.Window.WEEK.name },
                        label = { Text("This week", style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primaryContainer,
                            selectedLabelColor = colors.onPrimaryContainer))
                    FilterChip(selected = window == RideInsights.Window.MONTH,
                        onClick = { windowName = RideInsights.Window.MONTH.name },
                        label = { Text("This month", style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primaryContainer,
                            selectedLabelColor = colors.onPrimaryContainer))
                }
            }

            if (summary.rideCount == 0) {
                Text("No rides yet this $windowLabel. Trips appear here after your scooter reports them.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text("${number(summary.distanceKm, 1)}", color = colors.onSurface,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(" km ridden", Modifier.padding(start = 4.dp, bottom = 3.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Text("${summary.rideCount} ${if (summary.rideCount == 1) "ride" else "rides"} · ${formatDuration(summary.durationMinutes)}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

                HorizontalDivider(color = colors.outlineVariant)

                Text("₹${number(summary.savingsInr, 2)} saved vs petrol",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = colors.secondary)
                Text("Petrol for ${number(summary.distanceKm, 1)} km would cost about ₹${number(summary.petrolEquivalentCostInr, 2)}; you spent ₹${number(summary.electricityCostInr, 2)} on charging.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

                summary.bestEfficiencyWhPerKm?.let { best ->
                    HorizontalDivider(color = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("BEST EFFICIENCY RIDE", style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant, letterSpacing = 1.sp)
                            Text("${number(best, 1)} Wh/km", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${number(summary.bestEfficiencyDistanceKm, 1)} km",
                                style = MaterialTheme.typography.titleMedium)
                            Text(shortDate(summary.bestEfficiencyRideStartMs),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }

                Text("Petrol equivalent assumes a ${RideInsights.DEFAULT_PETROL_BIKE_KM_PER_LITRE.toInt()} km/L bike at ₹${number(petrolPricePerLitre, 0)}/L.",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

private fun formatDuration(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}h ${mins.toString().padStart(2, '0')}m" else "${mins}m"
}

private fun shortDate(epochMs: Long?): String = if (epochMs == null) ""
    else SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(epochMs))
