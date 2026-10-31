package io.ather.pro.ui.bluetooth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.ather.pro.ble.BleConnection
import io.ather.pro.ble.BleDeviceUi
import io.ather.pro.ble.BleUiState
import io.ather.pro.ble.BleVehicleProfile
import io.ather.pro.ble.QrPairing
import io.ather.pro.ble.VehicleLinkController
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
    vehicleLink: VehicleLinkController,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDeviceUi) -> Unit,
    onDisconnect: () -> Unit,
    onClearLogs: () -> Unit,
    onRefreshAdapterState: () -> Unit = {}
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasBlePermissions(context)) }

    LaunchedEffect(Unit) {
        onRefreshAdapterState()
    }

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
            payload.token != null -> {
                vehicleLink.registerToken(payload.token)
                if (!vehicleLink.state.value.enabled) {
                    vehicleLink.setEnabled(true)
                }
                qrNotice = "Pairing token registered: ${payload.token}. Searching for vehicle beacon…"
            }
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
        item { VehicleLinkCard(vehicleLink) }

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
                    FilledTonalIconButton(onClick = { vehicleLink.connectDirect(device.address) }) {
                        Icon(Icons.Default.Link, contentDescription = "Connect vehicle link to this device")
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

/**
 * Vehicle BLE link (Ather "Blueberry" profile): scan for the vehicle beacon,
 * bond, connect as GATT client and stream call/media/navigation payloads to
 * the dash. Protocol and pairing flow mirror the reverse-engineered app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleLinkCard(controller: VehicleLinkController) {
    val context = LocalContext.current
    val link by controller.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vehicle link", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Connect to the scooter dash over Bluetooth: calls, media and navigation on the dash",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = link.enabled, onCheckedChange = { on ->
                    if (on) {
                        val wanted = buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                                add(Manifest.permission.BLUETOOTH_SCAN)
                                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                                add(Manifest.permission.READ_PHONE_STATE)
                            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                        if (wanted.isEmpty()) controller.setEnabled(true)
                        else permissionLauncher.launch(wanted.toTypedArray()).let {
                            // Grant callback re-enables via direct call below.
                            controller.setEnabled(true)
                        }
                    } else controller.setEnabled(false)
                })
            }

            // Status: colored dot + label; vehicle name on its own line so long
            // names never run into the status text.
            val (dotColor, dotPulsing, statusLabel) = when (link.link) {
                io.ather.pro.ble.VehicleLinkController.LinkStatus.CONNECTED ->
                    Triple(Color(0xFF1DB584), false, "Connected")
                io.ather.pro.ble.VehicleLinkController.LinkStatus.SCANNING,
                io.ather.pro.ble.VehicleLinkController.LinkStatus.PAIRING,
                io.ather.pro.ble.VehicleLinkController.LinkStatus.CONNECTING ->
                    Triple(Color(0xFF26A69A), true, "Searching…")
                io.ather.pro.ble.VehicleLinkController.LinkStatus.PROFILE_MISMATCH ->
                    Triple(Color(0xFFE57373), false, "Wrong device")
                io.ather.pro.ble.VehicleLinkController.LinkStatus.OFF ->
                    Triple(MaterialTheme.colorScheme.onSurfaceVariant, false, "Off")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                io.ather.pro.ui.motion.StatusDot(color = dotColor, pulsing = dotPulsing)
                Text(statusLabel, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                androidx.compose.animation.AnimatedVisibility(visible = link.ignitionOn) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("Ignition on", Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val vehicleLabel = link.deviceName ?: link.deviceAddress
            AnimatedVisibility(
                visible = vehicleLabel != null,
                enter = fadeIn(io.ather.pro.ui.motion.Motion.standard()) + expandVertically(io.ather.pro.ui.motion.Motion.standard()),
                exit = fadeOut(io.ather.pro.ui.motion.Motion.standard()) + shrinkVertically(io.ather.pro.ui.motion.Motion.standard())
            ) {
                Text(vehicleLabel ?: "", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pairing token  ${link.token}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("token", link.token))
                }) { Text("Copy") }
            }

            var manualToken by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it.take(32) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Token from dash QR") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                TextButton(enabled = manualToken.isNotBlank(), onClick = {
                    controller.registerToken(manualToken.trim())
                    if (!controller.state.value.enabled) controller.setEnabled(true)
                    manualToken = ""
                }) { Text("Use") }
            }

            SmoothSection(visible = link.lastError != null) {
                Text(link.lastError.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            SmoothSection(visible = link.link == io.ather.pro.ble.VehicleLinkController.LinkStatus.CONNECTED) {
                Text("Test pushes to the dash", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalAction("Incoming call") { controller.sendCall("Mom", ringing = true) }
                    TonalAction("Now playing") { controller.sendMedia("Kaleidoscope", "Ather FM") }
                    TonalAction("Navigation") { controller.sendTbt("Turn left onto MG Road", BleVehicleProfile.TBT_TURN_LEFT, 180) }
                    TonalAction("WhatsApp") { controller.sendWhatsapp("Mom", "Ride safe!") }
                }
            }

            // Real-WhatsApp forwarding needs system notification-listener access.
            val ctx = LocalContext.current
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            var notifAccess by remember { mutableStateOf(io.ather.pro.ble.WhatsAppNotificationListener.hasAccess(ctx)) }
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        notifAccess = io.ather.pro.ble.WhatsAppNotificationListener.hasAccess(ctx)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            if (!notifAccess) {
                TextButton(onClick = {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text("Enable notification access — real WhatsApp on dash") }
            } else {
                Text("WhatsApp forwarding active", style = MaterialTheme.typography.labelMedium)
            }

            // Doze kills BLE on many phones until the screen wakes — the real app
            // requests battery-optimization exemption for exactly this reason.
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            val batteryExempt = remember { mutableStateOf(pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) }
            DisposableEffect(lifecycleOwner) {
                val batteryObs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        batteryExempt.value = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
                    }
                }
                lifecycleOwner.lifecycle.addObserver(batteryObs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(batteryObs) }
            }
            if (!batteryExempt.value) {
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + ctx.packageName)
                        ))
                    }
                }) { Text("Disable battery optimization — keep the dash connected") }
            }

            // Auto-SMS when a call is rejected from the dash (original: endCallAndSendMessage).
            val smsPrefs = remember {
                ctx.getSharedPreferences(BleVehicleProfile.PREFS_FILE, android.content.Context.MODE_PRIVATE)
            }
            var smsOn by remember {
                mutableStateOf(smsPrefs.getBoolean(BleVehicleProfile.PREF_SMS_ON_REJECT, false))
            }
            var smsText by remember {
                mutableStateOf(smsPrefs.getString(BleVehicleProfile.PREF_SMS_TEMPLATE, BleVehicleProfile.DEFAULT_SMS_TEMPLATE)
                    ?: BleVehicleProfile.DEFAULT_SMS_TEMPLATE)
            }
            val smsPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                smsPrefs.edit().putBoolean(BleVehicleProfile.PREF_SMS_ON_REJECT, granted).apply()
                smsOn = granted
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = smsOn, onCheckedChange = { want ->
                    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                    if (want && !granted) smsPermLauncher.launch(Manifest.permission.SEND_SMS)
                    else { smsPrefs.edit().putBoolean(BleVehicleProfile.PREF_SMS_ON_REJECT, want).apply(); smsOn = want }
                })
                Text("Auto-SMS when rejecting a call from the dash", style = MaterialTheme.typography.bodySmall)
            }
            if (smsOn) {
                OutlinedTextField(
                    value = smsText,
                    onValueChange = {
                        smsText = it
                        smsPrefs.edit().putString(BleVehicleProfile.PREF_SMS_TEMPLATE, it).apply()
                    },
                    label = { Text("Message sent to the caller") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false
                )
            }

            if (link.logs.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        link.logs.takeLast(20).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

/** Animated section wrapper: springs (no overshoot), fade + vertical expand. */
@Composable
private fun SmoothSection(visible: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(io.ather.pro.ui.motion.Motion.standard()) +
            expandVertically(animationSpec = io.ather.pro.ui.motion.Motion.standard()),
        exit = fadeOut(io.ather.pro.ui.motion.Motion.standard()) +
            shrinkVertically(animationSpec = io.ather.pro.ui.motion.Motion.standard())
    ) {
        androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** FilledTonalButton with press-down scale feedback (Apple: respond on press). */
@Composable
private fun TonalAction(label: String, onClick: () -> Unit) {
    val (source, scale) = io.ather.pro.ui.motion.rememberPressScale()
    FilledTonalButton(onClick = onClick, interactionSource = source, modifier = scale) {
        Text(label)
    }
}
