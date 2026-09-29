package io.ather.pro.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.ather.pro.data.auth.SecureSessionStore
import io.ather.pro.data.repository.AtherRepository
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.charging.ChargeLimitController
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.distinctUntilChanged
import io.ather.pro.domain.monitoring.MonitoringPolicy
import io.ather.pro.domain.monitoring.MonitoringState
import io.ather.pro.widget.DashboardWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android lifecycle policy lives here, outside the data repository and UI ViewModels. */
class MonitoringController(
    private val context: Context,
    private val repository: AtherRepository,
    private val sessionStore: SecureSessionStore
) {
    private val preferences = context.getSharedPreferences("ather_monitoring", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MonitoringState(alwaysEnabled = preferences.getBoolean("always_enabled", true)))
    val state = _state.asStateFlow()
    private var visible = false
    private var starting = false
    private var workerChecking = false
    private var restoreUntil = 0L
    val canCheck get() = sessionStore.current()?.isComplete == true && (_state.value.alwaysEnabled || repository.chargeLimit.value.enabled)

    val requested: Boolean
        get() = MonitoringPolicy.shouldRun(sessionStore.current()?.isComplete == true,
            _state.value.alwaysEnabled, repository.chargeLimit.value.enabled,
            ChargingControl.isActivelyCharging(repository.dashboard.value.telemetry),
            repository.chargeLimit.value.status == ChargeLimitController.Status.PENDING) ||
            (canCheck && _state.value.running && System.currentTimeMillis() < restoreUntil && repository.dashboard.value.chargingUpdatedAt == null)

    init {
        scope.launch {
            combine(sessionStore.session, repository.chargeLimit, repository.dashboard) { session, limit, dashboard ->
                Triple(session, limit.enabled, ChargingControl.isActivelyCharging(dashboard.telemetry) to (limit.status == ChargeLimitController.Status.PENDING))
            }.distinctUntilChanged().collect { (session, _, _) ->
                    if (session?.isComplete == true && (visible || _state.value.running || workerChecking) && !repository.hasCredentials()) {
                        repository.applyCredentials(session.token, session.vehicleUuid)
                    } else if (session == null) {
                        repository.clearCredentials()
                        DashboardWidgetUpdater.clear(context)
                    }
                    scheduleChecks()
                    reconcile()
                }
        }
        scope.launch {
            repository.authenticationRequired.collect { reason ->
                if (reason != null) sessionStore.clear()
            }
        }
        scope.launch(Dispatchers.IO) {
            combine(repository.dashboard, repository.chargeLimit) { dashboard, limit -> dashboard to limit }
                .collect { (dashboard, limit) ->
                    DashboardWidgetUpdater.publish(context, dashboard, limit)
                    delay(5_000)
                }
        }
    }

    fun onVisible() {
        visible = true
        sessionStore.current()?.takeIf { it.isComplete }?.let {
            repository.applyCredentials(it.token, it.vehicleUuid)
        }
        reconcile()
    }

    fun onHidden() {
        visible = false
        if (!requested && !_state.value.running && !starting && !workerChecking) repository.disconnect()
    }

    fun setAlwaysEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("always_enabled", enabled).apply()
        _state.update { it.copy(alwaysEnabled = enabled, error = null) }
        scheduleChecks()
        reconcile()
    }

    fun onServiceStarted() {
        restoreUntil = System.currentTimeMillis() + 45_000L
        scope.launch { delay(45_000); restoreUntil = 0; reconcile() }
        starting = false
        _state.update { it.copy(running = true, error = null) }
        sessionStore.current()?.takeIf { it.isComplete }?.let {
            repository.applyCredentials(it.token, it.vehicleUuid)
        }
    }

    fun onServiceStopped() {
        restoreUntil = 0
        starting = false
        _state.update { it.copy(running = false) }
        if (!visible && !workerChecking) repository.disconnect()
    }

    fun reportFailure() {
        starting = false
        _state.update { it.copy(running = false, error = "Android stopped background monitoring. Open the app and try again.") }
    }

    /** Notification action explicitly stops both monitoring and automatic charge commands. */
    fun stopFromNotification() {
        setAlwaysEnabled(false)
        repository.setChargeLimit(false, repository.chargeLimit.value.percent)
        context.stopService(Intent(context, ScooterMonitorService::class.java))
    }

    fun beginBackgroundCheck(): Boolean {
        if (workerChecking) return false
        workerChecking = true
        sessionStore.current()?.takeIf { it.isComplete }?.let { repository.applyCredentials(it.token, it.vehicleUuid) }
        return true
    }

    fun endBackgroundCheck() {
        workerChecking = false
        if (!visible && !_state.value.running) repository.disconnect()
    }

    private fun scheduleChecks() {
        val work = WorkManager.getInstance(context)
        if (!canCheck) {
            work.cancelUniqueWork(ChargingCheckWorker.WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ChargingCheckWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(ChargingCheckWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun reconcile() {
        if (!requested) {
            if (_state.value.running || starting) context.stopService(Intent(context, ScooterMonitorService::class.java))
            if (!visible && !starting && !_state.value.running && !workerChecking) repository.disconnect()
        } else if (visible && !_state.value.running && !starting) {
            starting = true
            try {
                ContextCompat.startForegroundService(context, Intent(context, ScooterMonitorService::class.java))
            } catch (_: RuntimeException) {
                reportFailure()
            }
        }
    }
}
