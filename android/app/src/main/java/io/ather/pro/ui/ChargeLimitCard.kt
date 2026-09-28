package io.ather.pro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ather.pro.domain.charging.ChargeLimitController
import kotlin.math.roundToInt

@Composable
fun ChargeLimitCard(
    snapshot: ChargeLimitController.Snapshot,
    onEnabledChange: (Boolean) -> Unit,
    onPercentChange: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var sliderValue by remember(snapshot.percent) {
        mutableFloatStateOf(snapshot.percent.toFloat())
    }
    val selectedPercent = sliderValue.roundToInt()
    val statusLabel = when (snapshot.status) {
        ChargeLimitController.Status.DISABLED -> "Off"
        ChargeLimitController.Status.MONITORING -> "Monitoring"
        ChargeLimitController.Status.PENDING -> "Pending"
        ChargeLimitController.Status.CONFIRMED -> "Confirmed"
        ChargeLimitController.Status.ERROR -> "Error"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Charge limit $statusLabel at ${snapshot.percent} percent"
            },
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CHARGE LIMIT",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "Stop at $selectedPercent%",
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Switch(
                    checked = snapshot.enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = colorScheme.secondary,
                        checkedThumbColor = colorScheme.onSecondary
                    )
                )
            }

            Text(
                text = "Selected: $selectedPercent%",
                color = colorScheme.secondary,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it.roundToInt().toFloat() },
                // Commit once when the rider releases the thumb. Moving the slider
                // also enables/arms the limit through MainActivity's callback.
                onValueChangeFinished = { onPercentChange(sliderValue.roundToInt()) },
                valueRange = ChargeLimitController.MIN_PERCENT.toFloat()..
                    ChargeLimitController.MAX_PERCENT.toFloat(),
                steps = ChargeLimitController.MAX_PERCENT - ChargeLimitController.MIN_PERCENT - 1,
                enabled = true,
                colors = SliderDefaults.colors(
                    thumbColor = colorScheme.secondary,
                    activeTrackColor = colorScheme.secondary
                ),
                modifier = Modifier.semantics {
                    contentDescription = "Charge limit percent slider"
                }
            )

            Text(
                text = "Status: $statusLabel",
                color = when (snapshot.status) {
                    ChargeLimitController.Status.ERROR -> colorScheme.error
                    ChargeLimitController.Status.CONFIRMED -> colorScheme.secondary
                    ChargeLimitController.Status.PENDING -> colorScheme.tertiary
                    else -> colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelMedium
            )

            snapshot.message?.let { msg ->
                Text(
                    text = msg,
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "Phone-app automation only — not a scooter firmware limit. " +
                    "If the phone is offline, force-stopped, or loses network, this limit will not enforce.",
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall
            )

            if (snapshot.status == ChargeLimitController.Status.ERROR) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.secondary)
                ) {
                    Text("Retry limit")
                }
            }
        }
    }
}
