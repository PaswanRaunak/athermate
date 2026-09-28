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
import androidx.core.app.ServiceCompat
import io.ather.pro.appContainer
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
    private var previousText: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
                    val stale = state.lastUpdated?.let { System.currentTimeMillis() - it > 60_000 } ?: true
                    val connection = when {
                        state.connection != ConnectionStatus.CONNECTED -> "Reconnecting"
                        stale -> "Waiting for scooter data"
                        else -> "Connected"
                    }
                    val soc = state.telemetry?.batterySoc?.takeIf(Double::isFinite)?.roundToInt()?.let { " · $it%" }.orEmpty()
                    val target = if (limit.enabled) " · Limit ${limit.percent}% (${limit.status.name.lowercase()})" else ""
                    val text = connection + soc + target
                    if (text != previousText) {
                        notifications.notify(NOTIFICATION_ID, notification(text))
                        previousText = text
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

    private fun notification(message: String): Notification = MonitorNotification.build(this, message)

    override fun onDestroy() {
        scope.cancel()
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
