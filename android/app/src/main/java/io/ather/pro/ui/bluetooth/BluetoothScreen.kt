package io.ather.pro.ui.bluetooth

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.ather.pro.ble.BleConnection
import io.ather.pro.ble.BleDeviceUi
import io.ather.pro.ble.BleUiState
import io.ather.pro.ble.QrPairing
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Direct BLE link to a scooter-side peripheral: scan, connect, live values and
 * a raw packet log for mapping an undocumented scooter's GATT payloads.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    // --- QR pairing -----------------------------------------------------------
    var showScanner by remember { mutableStateOf(false) }
    var qrTarget by remember { mutableStateOf<QrPairing.Payload?>(null) }
    var qrNotice by remember { mutableStateOf<String?>(null) }
    var unknownPayload by remember { mutableStateOf<String?>(null) }
    val latestState by rememberUpdatedState(state)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) showScanner = true
    }

    fun startQrPairing() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner = true
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun handleQrPayload(text: String) {
        val payload = QrPairing.parse(text)
        when {
            payload.address != null -> {
                qrTarget = payload
                qrNotice = "Looking for ${payload.address}. If nothing appears, put the scooter into pairing mode."
            }
            payload.name != null -> {
                qrTarget = payload
                qrNotice = "Looking for a device named \"${payload.name}\"…"
            }
            else -> unknownPayload = payload.raw
        }
    }

    LaunchedEffect(qrTarget) {
        val target = qrTarget ?: return@LaunchedEffect
        onStopScan()
        onScan()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val match = latestState.devices.firstOrNull { device ->
                (target.address != null && device.address.equals(target.address, ignoreCase = true)) ||
                    (!target.name.isNullOrBlank() && device.name.equals(target.name, ignoreCase = true))
            }
            if (match != null) {
                qrNotice = "Pairing with ${match.name}…"
                onConnect(match)
                qrTarget = null
                return@LaunchedEffect
            }
            if (latestState.connection == BleConnection.CONNECTED || latestState.connection == BleConnection.CONNECTING) {
                qrTarget = null
                return@LaunchedEffect
            }
            delay(400)
        }
        qrNotice = "No device matched the QR within 30 s. Try a manual scan and tap the scooter."
        qrTarget = null
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        qrNotice?.let { notice ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { qrNotice = null }) { Text("Dismiss") }
                    }
                }
            }
        }
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.connection == BleConnection.SCANNING) {
                            FilledTonalButton(onClick = onStopScan) { Text("Stop scan") }
                        } else {
                            Button(onClick = ::scanWithPermissions, enabled = state.adapterEnabled) { Text(if (state.connection == BleConnection.CONNECTED) "Scan again" else "Scan for devices") }
                        }
                        OutlinedButton(onClick = ::startQrPairing, enabled = state.adapterEnabled) {
                            Icon(Icons.Filled.QrCode2, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pair via QR")
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
            Card(onClick = { onConnect(device) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (device.scooterLike) {
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                                    Text("SCOOTER", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                        }
                        Text(device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SignalBars(device.rssi)
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

    if (showScanner) {
        Box(Modifier.fillMaxSize()) {
            QrScanScreen(
                onDecoded = { text ->
                    showScanner = false
                    handleQrPayload(text)
                },
                onClose = { showScanner = false }
            )
        }
    }

    unknownPayload?.let { raw ->
        AlertDialog(
            onDismissRequest = { unknownPayload = null },
            title = { Text("Unrecognised QR") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No pairing info was found in this QR. Copy the text below and share it so this format can be supported.")
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(raw, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText("qr", raw))
                    unknownPayload = null
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { unknownPayload = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun BleValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Three-bar signal meter; bars light up with proximity and turn green when strong. */
@Composable
private fun SignalBars(rssi: Int) {
    val colors = MaterialTheme.colorScheme
    val strength = when {
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(5, 9, 13).forEachIndexed { index, barHeight ->
                Box(Modifier.size(4.dp, barHeight.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (index < strength) colors.primary.copy(alpha = if (strength == 3) 1f else 0.55f)
                        else colors.surfaceContainerHighest))
            }
        }
        Text("$rssi", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp))
    }
}

private fun requiredBlePermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasBlePermissions(context: Context): Boolean = requiredBlePermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
