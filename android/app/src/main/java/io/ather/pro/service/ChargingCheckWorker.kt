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

/** Idle checks are scheduled by Android, without a notification. Active charging uses a silent FGS. */
class ChargingCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val monitor = container.monitoring
        if (!monitor.canCheck || monitor.state.value.running) return Result.success()
        var ownsForeground = false
        try {
            val startedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) { monitor.beginBackgroundCheck() }
            val fresh = withTimeoutOrNull(45_000) {
                container.repository.dashboard.first { (it.chargingUpdatedAt ?: 0) >= startedAt }
            } ?: return Result.retry()
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
                monitor.endBackgroundCheck()
            }
        }
    }

    companion object { const val WORK_NAME = "charging-and-widget-check" }
}
