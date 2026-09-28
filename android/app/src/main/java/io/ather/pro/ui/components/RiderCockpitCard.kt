package io.ather.pro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.battery.rideEfficiencyKmPerUnit
import io.ather.pro.domain.model.LiveRideObservation
import io.ather.pro.domain.model.ScooterDashboardState
import java.util.Locale

/**
 * Truthful ride HUD: Delta SoC from observed samples; km/unit from real
 * ride distance ÷ energy only (1 unit = 1 kWh). Never fabricates values.
 */
@Composable
fun RiderCockpitCard(
    dashboard: ScooterDashboardState,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val observation = dashboard.liveRideObservation()
    val kmPerUnit = rideEfficiencyKmPerUnit(dashboard.recentTrips)
    val vehicleState = dashboard.telemetry?.vehicleState?.replace('_', ' ')?.uppercase(Locale.US)
        ?: "UNKNOWN"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = cockpitContentDescription(observation, kmPerUnit, vehicleState)
            },
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "RIDER COCKPIT",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Observed live metrics · $vehicleState",
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CockpitMetric(
                    modifier = Modifier.weight(1f),
                    label = "Δ SoC (15m)",
                    value = formatDeltaSoc(observation.deltaSocPercent),
                    subtitle = when {
                        observation.sampleCount < 2 -> "Awaiting samples"
                        else -> "${observation.sampleCount} samples observed"
                    },
                    icon = Icons.Default.Speed
                )
                CockpitMetric(
                    modifier = Modifier.weight(1f),
                    label = "km/unit",
                    value = kmPerUnit?.let {
                        String.format(Locale.US, "%.2f", it)
                    } ?: "--",
                    subtitle = if (kmPerUnit != null) {
                        "Distance ÷ energy (1 unit = 1 kWh)"
                    } else {
                        "Need ride distance & energy"
                    },
                    icon = Icons.Default.ElectricBolt
                )
            }
        }
    }
}

@Composable
private fun CockpitMetric(
    modifier: Modifier,
    label: String,
    value: String,
    subtitle: String,
    icon: ImageVector
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                color = colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }
    }
}

private fun formatDeltaSoc(delta: Double?): String {
    if (delta == null || !delta.isFinite()) return "--"
    val sign = when {
        delta > 0.05 -> "-"
        delta < -0.05 -> "+"
        else -> ""
    }
    return String.format(Locale.US, "%s%.1f%%", sign, kotlin.math.abs(delta))
}

private fun cockpitContentDescription(
    observation: LiveRideObservation,
    kmPerUnit: Double?,
    vehicleState: String
): String {
    val delta = formatDeltaSoc(observation.deltaSocPercent)
    val eff = kmPerUnit?.let {
        String.format(Locale.US, "%.2f kilometers per unit from ride distance and energy", it)
    } ?: "kilometers per unit unavailable"
    return "Rider cockpit. Vehicle $vehicleState. Delta SoC $delta. $eff"
}
