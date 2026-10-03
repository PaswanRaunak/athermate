package io.ather.pro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.ui.components.*

/** Overview is presentation only; monitoring, persistence and widgets survive its disposal. */
@Composable
fun AtherDashboardScreen(
    dashboard: ScooterDashboardState,
    chargeLimit: ChargeLimitController.Snapshot,
    onOpenCharging: () -> Unit,
    onOpenMap: () -> Unit,
    onClearTrips: () -> Unit
) {
    var details by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { FreshnessLabel(dashboard) }
        dashboard.errorMessage?.let { error ->
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
            } }
        }
        item { EnergySummaryCard(dashboard, chargeLimit) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onOpenCharging, modifier = Modifier.weight(1f)) {
                    Text(if (chargeLimit.enabled) "Limit ${chargeLimit.percent}%" else "Manage charging")
                }
                OutlinedButton(onClick = onOpenMap, modifier = Modifier.weight(1f)) { Text("Find scooter") }
            }
        }
        item { ModeRangeCard(dashboard) }
        item { BatteryHistoryCard(dashboard = dashboard, chargeLimitPercent = chargeLimit.percent.takeIf { chargeLimit.enabled }) }
        item { TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth()) {
            Text(if (details) "Hide details" else "Battery health & vehicle details", fontWeight = FontWeight.SemiBold)
        } }
        if (details) {
            item { BatteryHealthCard(dashboard) }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("Odometer", style = MaterialTheme.typography.labelMedium)
                            Text("${number(dashboard.telemetry?.odoKm, 1)} km", style = MaterialTheme.typography.titleLarge) }
                        Column { Text("Fuel savings", style = MaterialTheme.typography.labelMedium)
                            Text("₹${number(dashboard.telemetry?.savingsInr)}", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
            item { TripHistoryPanel(trips = dashboard.recentTrips, tariffRate = dashboard.settings.tariffRatePerKWh, onClear = onClearTrips) }
            item { RiderCockpitCard(dashboard) }
            dashboard.telemetry?.let { telemetry ->
                item { ConnectivityMetaCard(telemetry) }
                if (telemetry.tpms?.hasPressure == true) item { TyreHealthCard(telemetry.tpms) }
            }
        }
    }
}
