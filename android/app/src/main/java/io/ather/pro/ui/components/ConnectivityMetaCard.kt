package io.ather.pro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.model.ScooterTelemetry

/**
 * Connectivity / charger / firmware metadata — only rows with source-backed values.
 * Returns early (renders nothing) when every field is absent.
 */
@Composable
fun ConnectivityMetaCard(
    telemetry: ScooterTelemetry,
    modifier: Modifier = Modifier
) {
    val rows = buildList {
        telemetry.connectivityStrength?.takeIf { it >= 0 }?.let {
            add("Connectivity" to "CSQ $it")
        }
        telemetry.chargerType?.takeIf { it.isNotBlank() }?.let {
            add("Charger type" to it)
        }
        // Ather emits chargerConnected=false/"Disconnected" for a moment while a
        // valid remote command is settling. Showing absence as an error is misleading,
        // so this metadata surface only renders affirmative/stable charging values.
        if (telemetry.chargerConnected == true) {
            add("Charger" to "Connected")
        }
        telemetry.chargingStatus
            ?.takeIf { it.isNotBlank() && !it.contains("disconnect", ignoreCase = true) }
            ?.let {
                add("Charge status" to it)
            }
        telemetry.displaySoftwareVersion?.let {
            add("Firmware" to it)
        }
    }
    if (rows.isEmpty()) return

    val colorScheme = MaterialTheme.colorScheme
    val description = rows.joinToString(separator = ". ") { "${it.first}: ${it.second}" }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Vehicle connectivity. $description"
            },
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "CONNECTIVITY & CHARGER",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = value,
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Shown only when vehicle reports the field",
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }
    }
}
