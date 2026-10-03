package io.ather.pro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ather.pro.data.auth.AtherAuthApi
import io.ather.pro.data.auth.AuthStep
import io.ather.pro.data.auth.SecureSessionStore
import io.ather.pro.data.repository.AtherRepository
import io.ather.pro.presentation.AtherDashboardViewModel
import io.ather.pro.presentation.AuthViewModel
import io.ather.pro.ui.AtherAppShell
import io.ather.pro.ui.auth.AuthScreen
import io.ather.pro.ui.theme.AtherProTheme
import io.ather.pro.util.ChargingNotificationManager

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled
    }

    private val sessionStore get() = appContainer.sessionStore
    private val authApi get() = appContainer.authApi
    private val repository get() = appContainer.repository

    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(sessionStore, authApi, repository)
    }

    private val dashboardViewModel: AtherDashboardViewModel by viewModels {
        AtherDashboardViewModel.Factory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ChargingNotificationManager.getInstance(applicationContext).createNotificationChannels()

        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }

        setContent {
            AtherProTheme {
                val authState by authViewModel.ui.collectAsStateWithLifecycle()
                val dashboard by dashboardViewModel.dashboard.collectAsStateWithLifecycle()
                val chargeLimit by dashboardViewModel.chargeLimit.collectAsStateWithLifecycle()
                val monitoring by appContainer.monitoring.state.collectAsStateWithLifecycle()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (authState.step != AuthStep.READY || authState.session?.isComplete != true) {
                        AuthScreen(
                            state = authState,
                            onPhoneChanged = authViewModel::onPhoneChanged,
                            onOtpChanged = authViewModel::onOtpChanged,
                            onRequestOtp = authViewModel::requestOtp,
                            onVerifyOtp = authViewModel::verifyOtp,
                            onSelectScooter = authViewModel::selectScooter,
                            onRetryScooters = authViewModel::retryScooters,
                            onBackToPhone = authViewModel::backToPhone
                        )
                    } else {
                        AtherAppShell(
                            session = requireNotNull(authState.session),
                            dashboard = dashboard,
                            chargeLimit = chargeLimit,
                            monitoring = monitoring,
                            onMonitoringChange = appContainer.monitoring::setAlwaysEnabled,
                            onRefresh = dashboardViewModel::refresh,
                            onModelChange = dashboardViewModel::setScooterModel,
                            onTariffChange = dashboardViewModel::setTariffRate,
                            onClearTrips = dashboardViewModel::clearTripHistory,
                            onPauseCharging = { dashboardViewModel.pauseCharging() },
                            onResumeCharging = { dashboardViewModel.resumeCharging() },
                            onClearRemoteChargingLatch = dashboardViewModel::clearRemoteChargingLatch,
                            onChargeLimitEnabledChange = { enabled ->
                                dashboardViewModel.setChargeLimit(enabled, chargeLimit.percent)
                            },
                            onChargeLimitPercentChange = { percent, power ->
                                dashboardViewModel.setChargeLimit(true, percent, power)
                            },
                            onChargeLimitRetry = dashboardViewModel::retryChargeLimit,
                            onLogout = authViewModel::logout
                        )
                    }
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        appContainer.monitoring.onVisible()
    }

    override fun onStop() {
        appContainer.monitoring.onHidden()
        super.onStop()
    }

}
