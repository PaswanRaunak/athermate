package io.ather.pro.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var selected by rememberSaveable(snapshot.percent) { mutableIntStateOf(snapshot.percent) }
    val pending = snapshot.status == ChargeLimitController.Status.PENDING
    val status = when (snapshot.status) {
        ChargeLimitController.Status.DISABLED -> "Off"
        ChargeLimitController.Status.MONITORING -> "Monitoring"
        ChargeLimitController.Status.PENDING -> "Waiting for scooter confirmation"
        ChargeLimitController.Status.CONFIRMED -> "Stop confirmed"
        ChargeLimitController.Status.ERROR -> "Needs attention"
    }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic charge limit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (snapshot.enabled) "${snapshot.percent}% · $status" else status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (snapshot.status == ChargeLimitController.Status.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
                if (snapshot.enabled) TextButton(onClick = { onEnabledChange(false) }) { Text("Turn off") }
            }
            Text("Stop at $selected%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Slider(value = selected.toFloat(), onValueChange = { selected = it.roundToInt() },
                valueRange = ChargeLimitController.MIN_PERCENT.toFloat()..ChargeLimitController.MAX_PERCENT.toFloat(),
                steps = ChargeLimitController.MAX_PERCENT - ChargeLimitController.MIN_PERCENT - 1,
                modifier = Modifier.semantics { contentDescription = "Charge target percent" })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 70, 80, 90, 100).forEach { target ->
                    FilterChip(selected = selected == target, onClick = { selected = target }, label = { Text("$target%") })
                }
            }
            Button(onClick = { onPercentChange(selected) }, modifier = Modifier.fillMaxWidth(),
                enabled = !pending && (!snapshot.enabled || selected != snapshot.percent)) {
                Text(if (snapshot.enabled) "Apply $selected% limit" else "Enable $selected% limit")
            }
            if (snapshot.enabled && selected != snapshot.percent) Text("New target has not been applied.", style = MaterialTheme.typography.bodySmall)
            snapshot.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (snapshot.status == ChargeLimitController.Status.ERROR) {
                OutlinedButton(onClick = onRetry) { Text("Retry stop at ${snapshot.percent}%") }
            }
            Text("Uses Pause at or above your target and retries until the scooter confirms it stopped. Keep this phone online with charging monitoring active.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.enabled) Text("Turn the limit off before resuming a charge above ${snapshot.percent}%.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
