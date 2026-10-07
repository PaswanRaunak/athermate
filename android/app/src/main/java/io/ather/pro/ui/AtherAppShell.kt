package io.ather.pro.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.ather.pro.data.auth.AuthSession
import io.ather.pro.domain.update.AppUpdateState
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ScooterArtwork
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.monitoring.MonitoringState
import io.ather.pro.ble.BleDeviceUi
import io.ather.pro.ble.BleUiState
import io.ather.pro.ui.bluetooth.BluetoothScreen
import io.ather.pro.ui.components.ChargeEstimateCard
import io.ather.pro.ui.components.FreshnessLabel
import io.ather.pro.ui.update.AppUpdateBanner

private enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home), CHARGING("Charging", Icons.Default.BatteryChargingFull),
    MAP("Map", Icons.Default.Map), BLUETOOTH("Bluetooth", Icons.Default.Bluetooth), SETTINGS("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtherAppShell(
    updateState: AppUpdateState,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: () -> Unit,
    session: AuthSession,
    dashboard: ScooterDashboardState,
    chargeLimit: ChargeLimitController.Snapshot,
    monitoring: MonitoringState,
    ble: BleUiState,
    onBleScan: () -> Unit,
    onBleStopScan: () -> Unit,
    onBleConnect: (BleDeviceUi) -> Unit,
    onBleDisconnect: () -> Unit,
    onBleClearLogs: () -> Unit,
    onMonitoringChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onModelChange: (ScooterModel) -> Unit,
    onArtworkColourChange: (String?) -> Unit,
    onTariffChange: (Double) -> Unit,
    onClearTrips: () -> Unit,
    onPauseCharging: () -> Unit,
    onResumeCharging: () -> Unit,
    onClearRemoteChargingLatch: () -> Unit,
    onChargeLimitEnabledChange: (Boolean) -> Unit,
    onChargeLimitPercentChange: (Int, Int) -> Unit,
    onChargeLimitRetry: () -> Unit,
    onLogout: () -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(Destination.HOME) }
    val stateHolder = rememberSaveableStateHolder()
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Bolt, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("ATHERMATE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(if (selected == Destination.HOME) dashboard.vehicleProfile?.displayName
                        ?: "My scooter" else selected.label,
                        style = MaterialTheme.typography.titleMedium)
                }
            }, actions = {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh scooter data") }
            })
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(selected = selected == destination, onClick = { selected = destination },
                        icon = { Icon(destination.icon, contentDescription = null) }, label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
        }
    ) { padding ->
        val shellColors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(padding)
            .background(Brush.verticalGradient(listOf(shellColors.background, shellColors.surfaceContainerLowest)))) {
            AppUpdateBanner(updateState, onOpenUpdate)
            Box(Modifier.weight(1f)) {
                stateHolder.SaveableStateProvider(selected.name) {
                    when (selected) {
                        Destination.HOME -> AtherDashboardScreen(dashboard, chargeLimit,
                            ScooterArtwork.accountTitle(session.displayName),
                            onOpenCharging = { selected = Destination.CHARGING }, onOpenMap = { selected = Destination.MAP },
                            onClearTrips = onClearTrips)
                        Destination.CHARGING -> LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { FreshnessLabel(dashboard) }
                            item { ChargeLimitCard(chargeLimit, dashboard, onChargeLimitEnabledChange, onChargeLimitPercentChange, onChargeLimitRetry) }
                            item { ChargeEstimateCard(dashboard, chargeLimit.percent) }
                            item { ChargingActions(dashboard.telemetry, dashboard.remoteChargingCommand,
                                onPauseCharging, onResumeCharging, onRetryLatch = onClearRemoteChargingLatch) }
                            item { MonitoringCard(monitoring, chargeLimit.enabled, onMonitoringChange) }
                        }
                        Destination.MAP -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { FreshnessLabel(dashboard) }
                            item { MapSection(gps = dashboard.telemetry?.gps, gpsUpdatedAt = dashboard.gpsUpdatedAt) }
                        }
                        Destination.BLUETOOTH -> BluetoothScreen(ble, onBleScan, onBleStopScan, onBleConnect, onBleDisconnect, onBleClearLogs)
                        Destination.SETTINGS -> SettingsScreen(session, dashboard, monitoring, chargeLimit.enabled,
                            updateState, onCheckUpdate, onOpenUpdate, onMonitoringChange, onModelChange,
                            onArtworkColourChange, onTariffChange, onLogout)
                    }
                }
            }
        }
    }
}
