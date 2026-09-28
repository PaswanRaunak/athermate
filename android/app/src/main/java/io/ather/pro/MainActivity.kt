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

    private val sessionStore by lazy { SecureSessionStore(applicationContext) }
    private val authApi by lazy { AtherAuthApi() }
    private val repository by lazy { AtherRepository.getInstance(applicationContext) }

    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(sessionStore, authApi, repository)
    }

    private val dashboardViewModel: AtherDashboardViewModel by viewModels {
        AtherDashboardViewModel.Factory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

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

        repository.onAuthenticationRequired = {
            runOnUiThread { authViewModel.logout() }
        }

        // Resume an existing valid encrypted session without forcing OTP again.
        sessionStore.current()?.takeIf { it.isComplete }?.let { session ->
            repository.applyCredentials(session.token, session.vehicleUuid)
        }

        setContent {
            AtherProTheme {
                val authState by authViewModel.ui.collectAsStateWithLifecycle()
                val dashboard by dashboardViewModel.dashboard.collectAsStateWithLifecycle()
                val chargeLimit by dashboardViewModel.chargeLimit.collectAsStateWithLifecycle()

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
                            onChargeLimitPercentChange = { percent ->
                                // Choosing a target is an explicit request to enforce it.
                                // This immediately evaluates current live SoC: at/above
                                // target sends Stop; below target monitors until crossing.
                                dashboardViewModel.setChargeLimit(true, percent)
                            },
                            onChargeLimitRetry = dashboardViewModel::retryChargeLimit,
                            onLogout = authViewModel::logout
                        )
                    }
                }
            }
        }
    }
}
