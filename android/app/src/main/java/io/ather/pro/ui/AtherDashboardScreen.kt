package io.ather.pro.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.battery.EstimatedBatteryHealth
import io.ather.pro.domain.battery.rideEfficiencyKmPerUnit
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ChargeCostEstimate
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ModeRange
import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.model.ScooterTelemetry

import io.ather.pro.domain.model.TripRecord
import io.ather.pro.ui.components.ConnectivityMetaCard
import io.ather.pro.ui.components.BatteryHistoryCard
import io.ather.pro.ui.components.RiderCockpitCard
import io.ather.pro.ui.components.TyreHealthCard
import io.ather.pro.widget.DashboardWidgetUpdater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtherDashboardScreen(
    dashboard: ScooterDashboardState,
    onRefresh: () -> Unit,
    onModelChange: (ScooterModel) -> Unit = {},
    onTariffChange: (Double) -> Unit = {},
    onClearTrips: () -> Unit = {},
    onPauseCharging: () -> Unit = {},
    onResumeCharging: () -> Unit = {},
    onClearRemoteChargingLatch: () -> Unit = {},
    chargeLimit: ChargeLimitController.Snapshot = ChargeLimitController.Snapshot(),
    onChargeLimitEnabledChange: (Boolean) -> Unit = {},
    onChargeLimitPercentChange: (Int) -> Unit = {},
    onChargeLimitRetry: () -> Unit = {}
) {
    val telemetry = dashboard.telemetry
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val statusColor = when (dashboard.connection) {
        ConnectionStatus.CONNECTED -> colorScheme.secondary
        ConnectionStatus.ERROR -> colorScheme.error
        else -> Color(0xFFFFC857)
    }

    LaunchedEffect(
        dashboard.telemetry?.batterySoc,
        dashboard.telemetry?.rangeKm,
        dashboard.telemetry?.mode,
        dashboard.lastUpdated,
        dashboard.connection
    ) {
        DashboardWidgetUpdater.publish(context, dashboard)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "ATHER PRO",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = dashboard.vehicleProfile?.displayName
                                ?: dashboard.settings.selectedModel.displayName,
                            color = colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "Connection Status: ${statusLabel(dashboard.connection)}"
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = statusLabel(dashboard.connection),
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    titleContentColor = colorScheme.onBackground
                )
            )
        },
        containerColor = colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Error Banner if any
            if (dashboard.errorMessage != null && dashboard.connection == ConnectionStatus.ERROR) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = dashboard.errorMessage,
                        modifier = Modifier.padding(14.dp),
                        color = colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (telemetry == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = colorScheme.secondary,
                            strokeWidth = 3.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Connecting to Ather...",
                            color = colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            } else {
                // 1. Hero Card: Clean, large Battery % + Live Range with sleek progress bar & Cost to Full
                HeroBatteryRangeCard(
                    telemetry = telemetry,
                    costEstimate = dashboard.costToFullCharge,
                    usableCapacityKWh = dashboard.settings.selectedModel.usableCapacityWh / 1000.0
                )

                RiderCockpitCard(dashboard = dashboard)

                ConnectivityMetaCard(telemetry = telemetry)

                // 2. Security & Stack Status Pills (Anti-Theft, Vacation Mode, Firmware)
                SecurityAndStackPills(telemetry = telemetry)

                // 3. Live WebSocket Telemetry Stream & Heartbeat Activity Graph
                TelemetryStreamActivityCard(dashboard = dashboard)

                // 4. Mode Grid: 4 clean, compact mode tiles
                ModeSelectorGrid(
                    modeRanges = telemetry.modeRanges,
                    activeMode = telemetry.mode
                )

                // 5. Quick Stats: 2x2 grid (Odometer, Battery Health, Fuel Saved, Efficiency)
                QuickStatsGrid(
                    telemetry = telemetry,
                    recentTrips = dashboard.recentTrips,
                    reportedSohPercentage = dashboard.reportedSohPercentage,
                    nominalCapacityWh = dashboard.settings.selectedModel.usableCapacityWh
                )

                BatteryHistoryCard(dashboard = dashboard)

                // Map Section (GPS live navigation with Find My Scooter)
                MapSection(gps = telemetry.gps)

                // Optional TPMS Tyre Pressure if present
                if (telemetry.tpms?.hasPressure == true) {
                    TyreHealthCard(telemetry.tpms)
                }

                // Always expose ride history; the empty state explains when the first
                // persisted sync-to-sync ride will appear.
                TripHistoryPanel(
                    trips = dashboard.recentTrips,
                    tariffRate = dashboard.settings.tariffRatePerKWh,
                    onClear = onClearTrips
                )
            }

            // Charging surface: explicit Start/Stop + persisted auto-stop slider.
            ChargingActions(
                telemetry = dashboard.telemetry,
                command = dashboard.remoteChargingCommand,
                onPauseCharging = onPauseCharging,
                onResumeCharging = onResumeCharging,
                onRetryLatch = onClearRemoteChargingLatch
            )
            ChargeLimitCard(
                snapshot = chargeLimit,
                onEnabledChange = onChargeLimitEnabledChange,
                onPercentChange = onChargeLimitPercentChange,
                onRetry = onChargeLimitRetry
            )

            dashboard.lastUpdated?.let { timestamp ->
                val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(timestamp))
                Text(
                    text = "Last synced at $time",
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                )
            }
        }
    }
}

/**
 * Security & Stack Status Pills
 * Badges use only values present in Ather telemetry; missing fields stay unknown.
 */
@Composable
private fun SecurityAndStackPills(
    telemetry: ScooterTelemetry,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isAntiTheft = telemetry.isAntiTheftArmed
    val isVacation = telemetry.isVacationModeActive
    val firmware = telemetry.displaySoftwareVersion

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Pill 1: Anti-Theft Armed
        Surface(
            modifier = Modifier.weight(1f),
            color = colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                1.dp,
                if (isAntiTheft == true) colorScheme.secondary.copy(alpha = 0.4f) else colorScheme.outline.copy(alpha = 0.2f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = if (isAntiTheft == true) colorScheme.secondary else colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(5.dp))
                Column {
                    Text(
                        text = "ANTI-THEFT",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = when (isAntiTheft) { true -> "Armed"; false -> "Disarmed"; null -> "Unknown" },
                        color = if (isAntiTheft == true) colorScheme.secondary else colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // Pill 2: Vacation Mode
        Surface(
            modifier = Modifier.weight(1f),
            color = colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                1.dp,
                if (isVacation == true) colorScheme.primary.copy(alpha = 0.4f) else colorScheme.outline.copy(alpha = 0.2f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BeachAccess,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = if (isVacation == true) colorScheme.primary else colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(5.dp))
                Column {
                    Text(
                        text = "VACATION",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = when (isVacation) { true -> "Active"; false -> "Standby"; null -> "Unknown" },
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // Pill 3: Atherstack Firmware
        Surface(
            modifier = Modifier.weight(1.1f),
            color = colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = colorScheme.secondary
                )
                Spacer(Modifier.width(5.dp))
                Column {
                    Text(
                        text = "ATHERSTACK",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = firmware ?: "Unknown",
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

/**
 * Live API Telemetry Stream & Heartbeat Activity Graph
 */
@Composable
private fun TelemetryStreamActivityCard(
    dashboard: ScooterDashboardState,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isConnected = dashboard.connection == ConnectionStatus.CONNECTED
    val packetCount = dashboard.packetCount
    val lastSyncTime = dashboard.lastUpdated?.let {
        SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(it))
    } ?: "Awaiting data"

    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header Row: Live pulse dot + Status + Packet counter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isConnected) {
                            Box(
                                modifier = Modifier
                                    .size((14 * pulseScale).dp)
                                    .clip(CircleShape)
                                    .background(colorScheme.secondary.copy(alpha = pulseAlpha))
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) colorScheme.secondary else colorScheme.outline)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isConnected) "LIVE TELEMETRY STREAM" else "TELEMETRY STREAM",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isConnected) "Live Stream Active" else "Stream Disconnected",
                            color = if (isConnected) colorScheme.secondary else colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        )
                    }
                }

                Surface(
                    color = colorScheme.background,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "$packetCount pkts",
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Heartbeat Sparkline Activity Graph
            TelemetryHeartbeatGraph(
                timestamps = dashboard.recentPacketTimestamps,
                isConnected = isConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "cerberus.ather.io • onchange stream",
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                )
                Text(
                    text = "Sync $lastSyncTime",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                )
            }
        }
    }
}

@Composable
private fun TelemetryHeartbeatGraph(
    timestamps: List<Long>,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.secondary
    val inactiveColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val midY = height / 2f

        // Draw subtle background center grid line
        drawLine(
            color = gridColor,
            start = Offset(0f, midY),
            end = Offset(width, midY),
            strokeWidth = 1f
        )

        val pointsCount = 24
        val stepX = width / (pointsCount - 1)
        val path = Path()

        // Bucket the actual packet timestamps into a rolling 60-second activity graph.
        // This replaces the previous fixed decorative waveform.
        val latestTimestamp = timestamps.lastOrNull() ?: System.currentTimeMillis()
        val windowStart = latestTimestamp - 60_000L
        val buckets = IntArray(pointsCount)
        timestamps.asSequence()
            .filter { it in windowStart..latestTimestamp }
            .forEach { timestamp ->
                val progress = ((timestamp - windowStart).toDouble() / 60_000.0).coerceIn(0.0, 1.0)
                val bucket = (progress * (pointsCount - 1)).roundToInt()
                buckets[bucket]++
            }
        val maxBucket = (buckets.maxOrNull() ?: 0).coerceAtLeast(1)
        val hasData = isConnected && buckets.any { it > 0 }

        for (i in 0 until pointsCount) {
            val x = i * stepX
            val activity = if (hasData) buckets[i].toFloat() / maxBucket else 0f
            val y = if (hasData) {
                height * 0.82f - activity * (height * 0.64f)
            } else {
                midY
            }

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = if (isConnected) primaryColor else inactiveColor,
            style = Stroke(
                width = 2.5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Draw last pulse dot at the end
        if (isConnected) {
            val lastX = width
            val lastActivity = if (hasData) buckets.last().toFloat() / maxBucket else 0f
            val lastY = if (hasData) {
                height * 0.82f - lastActivity * (height * 0.64f)
            } else {
                midY
            }
            drawCircle(
                color = primaryColor,
                radius = 3.5f,
                center = Offset(lastX, lastY)
            )
        }
    }
}

/**
 * Requirement 1: Hero Card
 * Clean, large Battery % (83.8%) + Live Range (79 km) with a sleek battery progress bar and Cost to Full Charge Estimator.
 */
@Composable
private fun HeroBatteryRangeCard(
    telemetry: ScooterTelemetry,
    costEstimate: ChargeCostEstimate,
    usableCapacityKWh: Double
) {
    val colorScheme = MaterialTheme.colorScheme
    val battery = telemetry.batterySoc?.coerceIn(0.0, 100.0)
    val activeMode = telemetry.mode
    val activeModeRange = activeMode?.let { telemetry.modeRanges[it]?.predictedRangeKm }
        ?: activeMode?.let { telemetry.modeRanges[it]?.rawRangeKm }
        ?: telemetry.rangeKm
    val progress = ((battery ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val isCharging = telemetry.charging == true

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp)
        ) {
            // Header Row: Status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SCOOTER STATUS",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                if (isCharging) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.secondary.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colorScheme.secondary)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "CHARGING",
                            color = colorScheme.secondary,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colorScheme.secondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                } else {
                    Text(
                        text = telemetry.vehicleState?.replace('_', ' ')?.uppercase() ?: "STATUS UNKNOWN",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Primary Metrics: Battery % + Live Range (Dynamically matched to active mode)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                // Left: Battery %
                Column {
                    Text(
                        text = "BATTERY",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = battery?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                            color = colorScheme.onSurface,
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 38.sp
                            )
                        )
                        Text(
                            text = "%",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp
                            ),
                            modifier = Modifier.padding(bottom = 6.dp, start = 3.dp)
                        )
                    }
                }

                // Right: Live Range for Active Mode
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "LIVE RANGE (${activeMode?.uppercase() ?: "UNKNOWN MODE"})",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = activeModeRange?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                            color = colorScheme.secondary,
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 38.sp
                            )
                        )
                        Text(
                            text = "km",
                            color = colorScheme.secondary.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp
                            ),
                            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Sleek Battery Progress Bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .semantics {
                        contentDescription = battery?.let {
                            "Battery level ${String.format(Locale.US, "%.1f", it)} percent"
                        } ?: "Battery level unavailable"
                    },
                color = if ((battery ?: 0.0) > 20) colorScheme.secondary else colorScheme.primary,
                trackColor = colorScheme.outline.copy(alpha = 0.4f)
            )

            // Charging ETA / Status info
            if (isCharging && (telemetry.timeToFullChargeMin != null || telemetry.timeToEightyChargeMin != null)) {
                Spacer(Modifier.height(10.dp))
                val etaText = when {
                    telemetry.timeToFullChargeMin != null -> "${telemetry.timeToFullChargeMin.toInt()} mins to 100%"
                    telemetry.timeToEightyChargeMin != null -> "${telemetry.timeToEightyChargeMin.toInt()} mins to 80%"
                    else -> ""
                }
                Text(
                    text = "⚡ $etaText",
                    color = colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Charge Needed vs Stored Usable Discharge Energy Row
            val storedDischargeKWh = battery?.let { usableCapacityKWh * (it / 100.0) }
            val neededChargeKWh = costEstimate.remainingKWh.takeIf { battery != null }
            val neededCostInr = costEstimate.costInr.takeIf { battery != null }

            Surface(
                color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "STORED DISCHARGE",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = storedDischargeKWh?.let {
                                "${String.format(Locale.US, "%.2f", it)} kWh ready"
                            } ?: "Unavailable",
                            color = colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(26.dp)
                            .width(1.dp)
                            .background(colorScheme.outline.copy(alpha = 0.3f))
                    )

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "NEEDED TO 100% FULL",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (neededChargeKWh != null && neededCostInr != null) {
                                "${String.format(Locale.US, "%.2f", neededChargeKWh)} kWh (₹${String.format(Locale.US, "%.2f", neededCostInr)})"
                            } else "Unavailable",
                            color = colorScheme.secondary,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Requirement 2: Mode Selector / Grid
 * 4 clean, compact mode tiles (SmartEco: 90 km, Ride: 74 km, Sport: 63 km, Warp: 50 km)
 * showing the real km at current % with subtle '100%: 107km' indicator. Active mode highlighted.
 */
@Composable
private fun ModeSelectorGrid(
    modeRanges: Map<String, ModeRange>,
    activeMode: String?
) {
    val colorScheme = MaterialTheme.colorScheme
    val modes = listOf(
        ModeConfig("SmartEco"),
        ModeConfig("Ride"),
        ModeConfig("Sport"),
        ModeConfig("Warp")
    )

    val activeRange = modes.firstOrNull { it.name.equals(activeMode, ignoreCase = true) }
        ?.let { config -> modeRanges[config.name]?.predictedRangeKm ?: modeRanges[config.name]?.rawRangeKm }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RIDING MODES · ATHER LIVE RANGE",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            if (activeMode != null) {
                Text(
                    text = "ACTIVE: ${activeMode.uppercase()}",
                    color = colorScheme.secondary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
            }
        }

        // 2x2 Grid for 4 modes
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Row 1: SmartEco & Ride
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModeTile(
                    modifier = Modifier.weight(1f),
                    config = modes[0],
                    modeRange = modeRanges["SmartEco"],
                    activeRangeKm = activeRange,
                    isActive = "SmartEco".equals(activeMode, ignoreCase = true)
                )
                ModeTile(
                    modifier = Modifier.weight(1f),
                    config = modes[1],
                    modeRange = modeRanges["Ride"],
                    activeRangeKm = activeRange,
                    isActive = "Ride".equals(activeMode, ignoreCase = true)
                )
            }

            // Row 2: Sport & Warp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModeTile(
                    modifier = Modifier.weight(1f),
                    config = modes[2],
                    modeRange = modeRanges["Sport"],
                    activeRangeKm = activeRange,
                    isActive = "Sport".equals(activeMode, ignoreCase = true)
                )
                ModeTile(
                    modifier = Modifier.weight(1f),
                    config = modes[3],
                    modeRange = modeRanges["Warp"],
                    activeRangeKm = activeRange,
                    isActive = "Warp".equals(activeMode, ignoreCase = true)
                )
            }
        }
    }
}

private data class ModeConfig(
    val name: String
)

@Composable
private fun ModeTile(
    modifier: Modifier = Modifier,
    config: ModeConfig,
    modeRange: ModeRange?,
    activeRangeKm: Double?,
    isActive: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val liveRangeKm = modeRange?.predictedRangeKm ?: modeRange?.rawRangeKm
    val diffFromActive = if (liveRangeKm != null && activeRangeKm != null) {
        liveRangeKm - activeRangeKm
    } else null

    val containerColor = if (isActive) colorScheme.surfaceVariant else colorScheme.surface
    val border = if (isActive) BorderStroke(1.5.dp, colorScheme.secondary) else null

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(18.dp),
        border = border
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Mode Header: Name & Active Indicator / Reach Gain
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = config.name,
                    color = if (isActive) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                )
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(colorScheme.secondary)
                    )
                } else if (diffFromActive != null && diffFromActive > 0.05) {
                    Text(
                        text = "+${String.format(Locale.US, "%.2f", diffFromActive)} km",
                        color = colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Primary Bold Real Range Number at Current % (2 decimals)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = liveRangeKm?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                    color = if (isActive) colorScheme.secondary else colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "km",
                    color = if (isActive) colorScheme.secondary else colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            Spacer(Modifier.height(5.dp))

            Text(
                text = if (modeRange?.predictedRangeKm != null) "Predicted by Ather" else if (modeRange?.rawRangeKm != null) "Reported by Ather" else "Awaiting telemetry",
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp
                )
            )
        }
    }
}

/**
 * Quick Stats: odometer, battery health (BMS or clearly named estimate), fuel saved,
 * and ride efficiency as km/unit from real distance÷energy only (1 unit = 1 kWh).
 */
@Composable
private fun QuickStatsGrid(
    telemetry: ScooterTelemetry,
    recentTrips: List<TripRecord>,
    reportedSohPercentage: Double? = null,
    nominalCapacityWh: Double = 0.0
) {
    val colorScheme = MaterialTheme.colorScheme

    val odoText = telemetry.odoKm?.let { "${String.format(Locale.US, "%,.2f", it)} km" } ?: "--"

    val health = EstimatedBatteryHealth.resolve(
        reportedBmsSohPercent = reportedSohPercentage,
        trips = recentTrips,
        nominalCapacityWh = nominalCapacityWh
    )
    val (healthLabel, healthText, healthSubtitle) = when (health) {
        is EstimatedBatteryHealth.Display.ReportedBms -> Triple(
            "BATTERY SoH",
            String.format(Locale.US, "%.1f%%", health.percent),
            "Reported BMS SoH from telemetry"
        )
        is EstimatedBatteryHealth.Display.Estimated -> Triple(
            "EST. BATTERY HEALTH",
            String.format(Locale.US, "%.1f%%", health.percent),
            "${health.sampleCount} samples · ${health.confidenceLabel}"
        )
        EstimatedBatteryHealth.Display.Unavailable -> Triple(
            "BATTERY HEALTH",
            "Unavailable",
            "Need official efficiency plus 5 local distance/SoC samples"
        )
    }

    val savingsText = telemetry.savingsInr?.let { "₹${String.format(Locale.US, "%,.2f", it)}" } ?: "--"

    // km/unit only from Σdistance / Σ(energy as kWh). No Wh/km inversion.
    val rideKmPerUnit = rideEfficiencyKmPerUnit(recentTrips)
    val (efficiencyText, efficiencySubtitle) = when {
        rideKmPerUnit != null ->
            String.format(Locale.US, "%.2f km/unit", rideKmPerUnit) to
                "Distance ÷ energy (1 unit = 1 kWh)"
        else -> "--" to "Need ride distance & energy"
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "QUICK STATS",
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "ODOMETER",
                value = odoText,
                icon = Icons.Default.Speed
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = healthLabel,
                value = healthText,
                icon = Icons.Default.Favorite,
                subtitle = healthSubtitle,
                accentColor = colorScheme.secondary
            )
        }

        if (health is EstimatedBatteryHealth.Display.Estimated) {
            Text(
                text = health.methodLabel,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "FUEL SAVED",
                value = savingsText,
                icon = Icons.Default.Savings
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = "km/unit",
                value = efficiencyText,
                subtitle = efficiencySubtitle,
                icon = Icons.Default.ElectricBolt
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: ImageVector,
    subtitle: String? = null,
    accentColor: Color? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp
                    )
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accentColor ?: colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = value,
                color = accentColor ?: colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            )

            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Normal
                    )
                )
            }
        }
    }
}


private fun statusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.CONNECTED -> "LIVE"
    ConnectionStatus.CONNECTING -> "CONNECTING"
    ConnectionStatus.DISCONNECTED -> "OFFLINE"
    ConnectionStatus.ERROR -> "ERROR"
}
