package io.ather.pro.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.ather.pro.appContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import io.ather.pro.widget.DashboardWidgetUpdater

/** Idle checks are scheduled by Android, without a notification. Active charging uses a silent FGS. */
class ChargingCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val monitor = container.monitoring
        val manualRefresh = inputData.getBoolean(MANUAL_REFRESH, false)
        if (container.sessionStore.current()?.isComplete != true) return Result.success()
        if (!manualRefresh && (!monitor.canCheck || monitor.state.value.running)) return Result.success()
        // A running service already owns the connection; leave its lifecycle alone.
        if (monitor.state.value.running) {
            container.repository.refresh()
            return Result.success()
        }
        var ownsForeground = false
        var ownsCheck = false
        try {
            val startedAt = System.currentTimeMillis()
            ownsCheck = withContext(Dispatchers.Main) { monitor.beginBackgroundCheck() }
            if (!ownsCheck) return Result.retry()
            if (manualRefresh) container.repository.refresh()
            val fresh = withTimeoutOrNull(45_000) {
                container.repository.dashboard.first {
                    ((if (manualRefresh) it.batteryUpdatedAt else it.chargingUpdatedAt) ?: 0) >= startedAt
                }
            } ?: return Result.retry()
            DashboardWidgetUpdater.publish(applicationContext, fresh, container.repository.chargeLimit.value)
            if (monitor.requested && !monitor.state.value.running) {
                try {
                    setForeground(ForegroundInfo(MonitorNotification.ID, MonitorNotification.build(applicationContext,
                        "Battery ${fresh.telemetry?.batterySoc?.roundToInt() ?: "—"}% · Monitoring your charge"), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0))
                } catch (_: RuntimeException) {
                    withContext(Dispatchers.Main) { monitor.reportFailure() }
                    return Result.retry()
                }
                ownsForeground = true
                withContext(Dispatchers.Main) { monitor.onServiceStarted() }
                var previous: String? = null
                while (currentCoroutineContext().isActive && monitor.requested) {
                    val dashboard = container.repository.dashboard.value
                    val limit = container.repository.chargeLimit.value
                    val text = "${dashboard.telemetry?.batterySoc?.roundToInt() ?: "—"}%" +
                        if (limit.enabled) " · Stop at ${limit.percent}% · ${limit.status.name.lowercase()}" else " · Charging"
                    if (previous != text) {
                        setForeground(ForegroundInfo(MonitorNotification.ID, MonitorNotification.build(applicationContext, text), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0))
                        previous = text
                    }
                    delay(5_000)
                }
            }
            return Result.success()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (ownsForeground) monitor.onServiceStopped()
                if (ownsCheck) monitor.endBackgroundCheck()
            }
        }
    }

    companion object {
        const val WORK_NAME = "charging-and-widget-check"
        const val MANUAL_REFRESH = "manual_widget_refresh"
    }
}
