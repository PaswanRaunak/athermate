package io.ather.pro.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.ather.pro.data.auth.AuthSession
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.monitoring.MonitoringState
import io.ather.pro.ui.components.ChargeEstimateCard
import io.ather.pro.ui.components.FreshnessLabel

private enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home), CHARGING("Charging", Icons.Default.BatteryChargingFull),
    MAP("Map", Icons.Default.Map), SETTINGS("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtherAppShell(
    session: AuthSession,
    dashboard: ScooterDashboardState,
    chargeLimit: ChargeLimitController.Snapshot,
    monitoring: MonitoringState,
    onMonitoringChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onModelChange: (ScooterModel) -> Unit,
    onTariffChange: (Double) -> Unit,
    onClearTrips: () -> Unit,
    onPauseCharging: () -> Unit,
    onResumeCharging: () -> Unit,
    onClearRemoteChargingLatch: () -> Unit,
    onChargeLimitEnabledChange: (Boolean) -> Unit,
    onChargeLimitPercentChange: (Int) -> Unit,
    onChargeLimitRetry: () -> Unit,
    onLogout: () -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(Destination.HOME) }
    val stateHolder = rememberSaveableStateHolder()
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("ATHR+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(if (selected == Destination.HOME) dashboard.vehicleProfile?.displayName
                        ?: dashboard.settings.selectedModel.displayName else selected.label,
                        style = MaterialTheme.typography.titleMedium)
                }
            }, actions = {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh scooter data") }
            })
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(selected = selected == destination, onClick = { selected = destination },
                        icon = { Icon(destination.icon, contentDescription = null) }, label = { Text(destination.label) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            stateHolder.SaveableStateProvider(selected.name) {
                when (selected) {
                    Destination.HOME -> AtherDashboardScreen(dashboard, chargeLimit,
                        onOpenCharging = { selected = Destination.CHARGING }, onOpenMap = { selected = Destination.MAP }, onClearTrips)
                    Destination.CHARGING -> LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item { FreshnessLabel(dashboard) }
                        item { ChargeLimitCard(chargeLimit, onChargeLimitEnabledChange, onChargeLimitPercentChange, onChargeLimitRetry) }
                        item { ChargeEstimateCard(dashboard, chargeLimit.percent) }
                        item { ChargingActions(dashboard.telemetry, dashboard.remoteChargingCommand,
                            onPauseCharging, onResumeCharging, onRetryLatch = onClearRemoteChargingLatch) }
                        item { MonitoringCard(monitoring, chargeLimit.enabled, onMonitoringChange) }
                    }
                    Destination.MAP -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { FreshnessLabel(dashboard) }
                        item { MapSection(gps = dashboard.telemetry?.gps, gpsUpdatedAt = dashboard.gpsUpdatedAt) }
                    }
                    Destination.SETTINGS -> SettingsScreen(session, dashboard, monitoring, chargeLimit.enabled,
                        onMonitoringChange, onModelChange, onTariffChange, onLogout)
                }
            }
        }
    }
}
