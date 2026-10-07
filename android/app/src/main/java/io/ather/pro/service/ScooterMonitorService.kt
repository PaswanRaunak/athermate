package io.ather.pro.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import io.ather.pro.appContainer
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.model.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One foreground service owns continuous cloud telemetry, including when the UI closes. */
class ScooterMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var previousKey: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cutoffWakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appContainer.monitoring.stopFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        // Promote before session/database/network work to meet Android's FGS deadline.
        val notifications = getSystemService(NotificationManager::class.java)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Connecting to your scooter…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: RuntimeException) {
            appContainer.monitoring.reportFailure()
            stopSelf()
            return START_NOT_STICKY
        }
        appContainer.monitoring.onServiceStarted()
        if (!appContainer.monitoring.requested) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            observeNetwork()
            scope.launch {
                while (isActive) {
                    val state = appContainer.repository.dashboard.value
                    val limit = appContainer.repository.chargeLimit.value
                    keepCutoffAwake(limit.enabled && appContainer.monitoring.requested)
                    val stale = state.lastUpdated?.let { System.currentTimeMillis() - it > 60_000 } ?: true
                    val connection = when {
                        state.connection != ConnectionStatus.CONNECTED -> "Reconnecting"
                        stale -> "Waiting for scooter data"
                        else -> "Connected"
                    }
                    val telemetry = state.telemetry
                    val chargingNow = ChargingControl.isActivelyCharging(telemetry)
                    val socInt = telemetry?.batterySoc?.takeIf(Double::isFinite)?.roundToInt()
                    val soc = socInt?.let { " · $it%" }.orEmpty()
                    val target = if (limit.enabled) " · Limit ${limit.percent}% (${limit.status.name.lowercase()})" else ""
                    val stopTime = if (limit.enabled && limit.status == io.ather.pro.domain.charging.ChargeLimitController.Status.MONITORING)
                        limit.estimate?.let { " · Est. stop " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(it.stopAtMs)) }.orEmpty() else ""
                    val text = connection + soc + target + stopTime
                    // Charging state and SoC drive the progress bar, so re-post on their change too.
                    val key = "$text#${chargingNow}#${socInt ?: -1}"
                    if (key != previousKey) {
                        notifications.notify(NOTIFICATION_ID, notification(text, socInt, chargingNow))
                        previousKey = key
                    }
                    delay(5_000)
                }
            }
        }
        return START_STICKY
    }

    private fun observeNetwork() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                appContainer.repository.refresh()
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun notification(message: String, socPercent: Int? = null, charging: Boolean = false): Notification =
        MonitorNotification.build(this, message, socPercent, charging)

    /** Keep fixed snapshot checks running with the screen off whenever the limiter is enabled. */
    private fun keepCutoffAwake(required: Boolean) {
        if (!required) {
            cutoffWakeLock?.takeIf { it.isHeld }?.release()
            return
        }
        val lock = cutoffWakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AtherMate:ChargeCutoff")
            .apply { setReferenceCounted(false) }.also { cutoffWakeLock = it }
        val now = SystemClock.elapsedRealtime()
        if (!lock.isHeld || now - wakeLockRenewedAt >= 60_000L) {
            lock.acquire(120_000L)
            wakeLockRenewedAt = now
        }
    }

    override fun onDestroy() {
        scope.cancel()
        keepCutoffAwake(false)
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        appContainer.monitoring.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = MonitorNotification.ID
        private const val ACTION_STOP = "io.ather.pro.STOP_MONITORING"
    }
}
