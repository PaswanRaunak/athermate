package io.ather.pro.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.ui.theme.themeGlassSurface
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
    vehicleLink: io.ather.pro.ble.VehicleLinkController,
    onBleScan: () -> Unit,
    onBleStopScan: () -> Unit,
    onBleConnect: (BleDeviceUi) -> Unit,
    onBleDisconnect: () -> Unit,
    onBleClearLogs: () -> Unit,
    onBleRefreshAdapterState: () -> Unit = {},
    onMonitoringChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onModelChange: (ScooterModel) -> Unit,
    onArtworkColourChange: (String?) -> Unit,
    onTariffChange: (Double) -> Unit,
    onClearTrips: () -> Unit,
    onClearChargingSessions: () -> Unit,
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
    ) { padding ->
        val shellColors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(padding)
            .background(Brush.verticalGradient(listOf(shellColors.background, shellColors.surfaceContainerLowest)))) {
            AppUpdateBanner(updateState, onOpenUpdate)
            // JWT sessions expire server-side with no refresh flow — warn while
            // there is still time to re-sign-in without losing live coverage.
            session.expiresAtEpochSec?.let { expSec ->
                val remainingSec = expSec - System.currentTimeMillis() / 1000L
                if (remainingSec in 0..(48L * 3600L)) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null,
                                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Column {
                                Text("Session expiring soon",
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (remainingSec < 3600L) "Your sign-in expires within the hour. After that, sign in again with an OTP to keep live data."
                                    else "Your sign-in expires in about ${remainingSec / 3600L} h. After that, sign in again with an OTP to keep live data.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                stateHolder.SaveableStateProvider(selected.name) {
                    when (selected) {
                        Destination.HOME -> AtherDashboardScreen(dashboard, chargeLimit,
                            ScooterArtwork.accountTitle(session.displayName),
                            onOpenCharging = { selected = Destination.CHARGING }, onOpenMap = { selected = Destination.MAP },
                            onClearTrips = onClearTrips)
                        Destination.CHARGING -> LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { FreshnessLabel(dashboard) }
                            item { ChargeLimitCard(chargeLimit, dashboard, onChargeLimitEnabledChange, onChargeLimitPercentChange, onChargeLimitRetry) }
                            item { ChargeEstimateCard(dashboard, chargeLimit.percent) }
                            item { ChargingActions(dashboard.telemetry, dashboard.remoteChargingCommand,
                                onPauseCharging, onResumeCharging, onRetryLatch = onClearRemoteChargingLatch) }
                            item { MonitoringCard(monitoring, chargeLimit.enabled, onMonitoringChange) }
                            item { ChargingHistoryCard(dashboard.chargingSessions, onClear = onClearChargingSessions) }
                        }
                        Destination.MAP -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { FreshnessLabel(dashboard) }
                            item { MapSection(gps = dashboard.telemetry?.gps, gpsUpdatedAt = dashboard.gpsUpdatedAt) }
                        }
                        Destination.BLUETOOTH -> BluetoothScreen(ble, vehicleLink, onBleScan, onBleStopScan, onBleConnect, onBleDisconnect, onBleClearLogs, onBleRefreshAdapterState)
                        Destination.SETTINGS -> SettingsScreen(session, dashboard, monitoring, chargeLimit.enabled,
                            updateState, onCheckUpdate, onOpenUpdate, onMonitoringChange, onModelChange,
                            onArtworkColourChange, onTariffChange, onLogout)
                    }
                }
                // Floating glass navigation pill.
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 22.dp, end = 22.dp, bottom = 10.dp)
                        .fillMaxWidth()
                        .shadow(18.dp, RoundedCornerShape(28.dp)),
                    shape = RoundedCornerShape(28.dp),
                    color = Color.Transparent
                ) {
                    Row(Modifier
                        .background(Brush.verticalGradient(listOf(Color(0xFF182019), Color(0xFF0C100D))))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Destination.entries.forEach { destination ->
                            val sel = selected == destination
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                                    .clickable { selected = destination }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    Modifier.size(width = 42.dp, height = 30.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (sel) MaterialTheme.colorScheme.primaryContainer
                                            else Color.Transparent
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(destination.icon, contentDescription = destination.label,
                                        modifier = Modifier.size(19.dp),
                                        tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(destination.label, fontSize = 9.5.sp, maxLines = 1,
                                    letterSpacing = 0.2.sp,
                                    color = if (sel) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
