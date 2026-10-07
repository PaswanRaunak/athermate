package io.ather.pro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.RemoteCommandPhase
import io.ather.pro.domain.model.ScooterTelemetry
import kotlinx.coroutines.delay

@Composable
fun ChargingActions(
    telemetry: ScooterTelemetry?,
    command: RemoteChargingCommand,
    onPauseCharging: () -> Unit,
    onResumeCharging: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLatch: () -> Unit = {}
) {
    // Recompose while a latch is pending so confirm timeouts unlock buttons
    // even if WebSocket telemetry is quiet.
    var clockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(command.phase, command.requestedAt) {
        if (command.phase != RemoteCommandPhase.SENDING &&
            command.phase != RemoteCommandPhase.ACCEPTED
        ) {
            return@LaunchedEffect
        }
        while (true) {
            delay(1_000L)
            clockMs = System.currentTimeMillis()
        }
    }
    val view = ChargingControl.resolveView(telemetry, command, nowMs = clockMs, confirmFromTelemetry = false)
    ChargingActions(
        view = view,
        onPauseCharging = onPauseCharging,
        onResumeCharging = onResumeCharging,
        onRetryLatch = onRetryLatch,
        modifier = modifier
    )
}

/** Remote charge control: status-led card with the one action that is currently possible. */
@Composable
private fun ChargingActions(
    view: ChargingControl.View,
    onPauseCharging: () -> Unit,
    onResumeCharging: () -> Unit,
    onRetryLatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val charging = view.activelyCharging
    val plugged = view.pluggedIn
    val command = view.command

    // The status color is the whole story: green = current flowing, amber = cable in,
    // grey = nothing to control.
    val (statusColor, statusText) = when {
        charging -> colors.primary to "Charging"
        plugged -> colors.tertiary to "Paused on charger"
        else -> colors.onSurfaceVariant to "Charger not connected"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Charging status: $statusText"
            },
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor))
                    Text(statusText.uppercase(), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, color = statusColor)
                }
                if (plugged && !charging) {
                    Surface(color = colors.tertiaryContainer, shape = RoundedCornerShape(50)) {
                        Text("PLUGGED IN", color = colors.onTertiaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }

            Text(
                text = command.message ?: if (charging) {
                    "Vehicle is drawing power. Pause remotely to protect the battery or stop at your limit."
                } else if (plugged) {
                    "Cable connected but no current. Resume charging from here when you're ready."
                } else {
                    "Plug the charger into the scooter to control charging remotely."
                },
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

            // One deliberate action per state — the disabled phase keeps it visible.
            when {
                view.commandPending -> {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Waiting for scooter…")
                    }
                }
                charging && view.canPause -> {
                    Button(onClick = onPauseCharging, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Pause, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause charging")
                    }
                }
                plugged && view.canResume -> {
                    Button(onClick = onResumeCharging, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Resume charging")
                    }
                }
                else -> {
                    Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Nothing to control right now",
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                    }
                }
            }

            when (command.phase) {
                RemoteCommandPhase.SENDING -> Text(
                    view.command.message ?: "Dispatching request to server…",
                    color = colors.tertiary, style = MaterialTheme.typography.labelSmall)
                RemoteCommandPhase.ACCEPTED -> {
                    Text(
                        view.command.message ?: "Request accepted — waiting for scooter telemetry confirmation…",
                        color = colors.tertiary, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = onRetryLatch) { Text("Allow another attempt") }
                }
                RemoteCommandPhase.ERROR -> {
                    Text(
                        view.command.message ?: "Request failed or timed out — you can retry.",
                        color = colors.error, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = onRetryLatch) { Text("Dismiss error") }
                }
                RemoteCommandPhase.CONFIRMED -> Text(
                    view.command.message ?: "Scooter confirmed the charging change.",
                    color = colors.primary, style = MaterialTheme.typography.labelSmall)
                RemoteCommandPhase.IDLE -> Unit
            }
        }
    }
}
