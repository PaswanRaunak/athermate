package io.ather.pro.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.model.TpmsData
import io.ather.pro.util.VehicleAlertEvaluator
import java.util.Locale

/** TPMS card with low-pressure warning styling; hidden by caller when pressure absent. */
@Composable
fun TyreHealthCard(
    tpms: TpmsData,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val frontLow = tpms.frontPressurePsi?.let { it < VehicleAlertEvaluator.TPMS_LOW_PSI } == true
    val rearLow = tpms.rearPressurePsi?.let { it < VehicleAlertEvaluator.TPMS_LOW_PSI } == true
    val warning = frontLow || rearLow

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Tyre pressure. ")
                    append(tpms.frontPressurePsi?.let {
                        "Front ${formatPsi(it)}${if (frontLow) " low" else ""}"
                    } ?: "Front unavailable")
                    append(". ")
                    append(tpms.rearPressurePsi?.let {
                        "Rear ${formatPsi(it)}${if (rearLow) " low" else ""}"
                    } ?: "Rear unavailable")
                }
            },
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = if (warning) BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.55f)) else null
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "TYRE PRESSURE (TPMS)",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            if (warning) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Low pressure below ${VehicleAlertEvaluator.TPMS_LOW_PSI.toInt()} psi",
                    color = colorScheme.error,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TyreSideCard(
                    modifier = Modifier.weight(1f),
                    side = "FRONT",
                    psi = tpms.frontPressurePsi,
                    tempC = tpms.frontTemperatureC,
                    low = frontLow
                )
                TyreSideCard(
                    modifier = Modifier.weight(1f),
                    side = "REAR",
                    psi = tpms.rearPressurePsi,
                    tempC = tpms.rearTemperatureC,
                    low = rearLow
                )
            }
        }
    }
}

@Composable
private fun TyreSideCard(
    modifier: Modifier,
    side: String,
    psi: Double?,
    tempC: Double?,
    low: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val accent: Color = if (low) colorScheme.error else colorScheme.onSurface
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (low) {
                colorScheme.errorContainer.copy(alpha = 0.35f)
            } else {
                colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = side,
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = psi?.let { formatPsi(it) } ?: "--",
                color = accent,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            tempC?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = String.format(Locale.US, "%.0f°C", it),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }
            if (low) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "LOW",
                    color = colorScheme.error,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

private fun formatPsi(psi: Double): String =
    String.format(Locale.US, "%.1f PSI", psi)
