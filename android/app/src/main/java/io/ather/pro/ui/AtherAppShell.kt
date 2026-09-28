package io.ather.pro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ather.pro.data.auth.AuthSession
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel

/** Signed-in application shell. Keeps all real-data surfaces reachable. */
@Composable
fun AtherAppShell(
    session: AuthSession,
    dashboard: ScooterDashboardState,
    chargeLimit: ChargeLimitController.Snapshot,
    onRefresh: () -> Unit,
    onModelChange: (ScooterModel) -> Unit,
    onTariffChange: (Double) -> Unit,
    onClearTrips: () -> Unit,
    onPauseCharging: () -> Unit,
    onResumeCharging: () -> Unit,
    onClearRemoteChargingLatch: () -> Unit,
    onChargeLimitEnabledChange: (Boolean) -> Unit,
    onChargeLimitPercentChange: (Int) -> Unit,
    onChargeLimitRetry: () -> Unit,
    onLogout: () -> Unit
) {
    // Keep the signed-in app focused on the scooter dashboard. Charger-map and
    // analytics navigation were explicitly removed.
    Box(modifier = Modifier.fillMaxSize()) {
        AtherDashboardScreen(
            dashboard = dashboard,
            onRefresh = onRefresh,
            onModelChange = onModelChange,
            onTariffChange = onTariffChange,
            onClearTrips = onClearTrips,
            onPauseCharging = onPauseCharging,
            onResumeCharging = onResumeCharging,
            onClearRemoteChargingLatch = onClearRemoteChargingLatch,
            chargeLimit = chargeLimit,
            onChargeLimitEnabledChange = onChargeLimitEnabledChange,
            onChargeLimitPercentChange = onChargeLimitPercentChange,
            onChargeLimitRetry = onChargeLimitRetry
        )

        IconButton(
            onClick = onLogout,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ExitToApp,
                contentDescription = "Sign out",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
