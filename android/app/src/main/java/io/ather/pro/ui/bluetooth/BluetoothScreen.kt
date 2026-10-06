package io.ather.pro.ui.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.ather.pro.ble.BleConnection
import io.ather.pro.ble.BleDeviceUi
import io.ather.pro.ble.BleUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Direct BLE link to a scooter-side peripheral: scan, connect, live values and
 * a raw packet log for mapping an undocumented scooter's GATT payloads.
 */
@Composable
fun BluetoothScreen(
    state: BleUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDeviceUi) -> Unit,
    onDisconnect: () -> Unit,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasBlePermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        if (granted) onScan()
    }

    fun scanWithPermissions() {
        if (granted) onScan()
        else permissionLauncher.launch(requiredBlePermissions().toTypedArray())
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            when {
                                !state.adapterEnabled -> Icons.Default.BluetoothDisabled
                                state.connection == BleConnection.SCANNING -> Icons.Default.BluetoothSearching
                                else -> Icons.Default.Bluetooth
                            },
                            contentDescription = null,
                            tint = if (state.connection == BleConnection.CONNECTED) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column {
                            Text(
                                when {
                                    !state.adapterPresent -> "No Bluetooth on this device"
                                    !state.adapterEnabled -> "Bluetooth is turned off"
                                    state.connection == BleConnection.SCANNING -> "Scanning…"
                                    state.connection == BleConnection.CONNECTING -> "Connecting to ${state.connectingTo ?: "device"}…"
                                    state.connection == BleConnection.CONNECTED -> "Connected over Bluetooth"
                                    else -> "Bluetooth ready"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Pair with a scooter-side BLE peripheral for live data without the cloud",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.connection == BleConnection.SCANNING) {
                            FilledTonalButton(onClick = onStopScan) { Text("Stop scan") }
                        } else {
                            Button(onClick = ::scanWithPermissions, enabled = state.adapterEnabled) { Text(if (state.connection == BleConnection.CONNECTED) "Scan again" else "Scan for devices") }
                        }
                        if (state.connection == BleConnection.CONNECTED || state.connection == BleConnection.CONNECTING) {
                            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Live data", style = MaterialTheme.typography.titleMedium)
                    val snapshot = state.snapshot
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BleValue("Battery", snapshot.batteryPercent?.let { "$it%" } ?: "—")
                        BleValue("Speed", snapshot.speedKmh?.let { "${it.toInt()} km/h" } ?: "—")
                        BleValue("Odometer", snapshot.odoKm?.let { "%.1f km".format(it) } ?: "—")
                    }
                    Text(
                        if (snapshot.lastUpdated == 0L) "Waiting for the first packet…"
                        else "Updated ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(snapshot.lastUpdated))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text(
                if (state.devices.isEmpty()) "No devices yet — start a scan"
                else "Devices (${state.devices.size}) — tap to connect",
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(state.devices, key = { it.address }) { device ->
            Card(onClick = { onConnect(device) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name + if (device.scooterLike) "  ✦" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Packet log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearLogs) { Text("Clear") }
                    }
                    Text(
                        "Every notification is logged in hex — connect once, read the packets, then teach BleParser the real format.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.logs.isNotEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                state.logs.takeLast(30).forEach { line ->
                                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BleValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun requiredBlePermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasBlePermissions(context: Context): Boolean = requiredBlePermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
