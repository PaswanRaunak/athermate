package io.ather.pro.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.ather.pro.MainActivity
import io.ather.pro.R
import io.ather.pro.domain.monitoring.MaintenancePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** UI-facing projection of the maintenance schedule. */
data class MaintenanceSnapshot(
    val serviceInitialised: Boolean = false,
    val nextDueOdoKm: Double? = null,
    val kmRemaining: Double? = null,
    val lastTyreCheckEpochMonth: Long? = null
)

/**
 * Owns the persisted maintenance latches, evaluates them against live odometer
 * readings, posts one reminder per boundary, and exposes the schedule to the UI.
 * Odometer decreases ( Resets / pack swaps ) are ignored by [MaintenancePolicy].
 */
class MaintenanceCenter private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val _snapshot = MutableStateFlow(readSnapshot())
    val snapshot: StateFlow<MaintenanceSnapshot> = _snapshot.asStateFlow()

    private fun readSnapshot() = MaintenanceSnapshot(
        serviceInitialised = prefs.contains(KEY_NEXT_SERVICE_ODO),
        nextDueOdoKm = prefs.getDoubleOrNull(KEY_NEXT_SERVICE_ODO),
        kmRemaining = null,
        lastTyreCheckEpochMonth = if (prefs.contains(KEY_TYRE_CHECKED_MONTH))
            prefs.getLong(KEY_TYRE_CHECKED_MONTH, 0L) else null
    )

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Odometer-based service and monthly tyre-check reminders"
                    setShowBadge(true)
                }
            )
        }
    }

    /** Called from the monitoring pipeline with every fresh dashboard state. */
    fun evaluate(odometerKm: Double?, nowMs: Long = System.currentTimeMillis()) {
        var serviceLatch = MaintenancePolicy.ServiceLatch(
            nextDueOdoKm = prefs.getDoubleOrNull(KEY_NEXT_SERVICE_ODO),
            lastNotifiedDueOdoKm = prefs.getDoubleOrNull(KEY_SERVICE_NOTIFIED_ODO)
        )
        val service = MaintenancePolicy.evaluateService(serviceLatch, odometerKm)
        serviceLatch = service.latch
        serviceLatch.nextDueOdoKm?.let { prefs.edit().putDouble(KEY_NEXT_SERVICE_ODO, it).apply() }
        serviceLatch.lastNotifiedDueOdoKm?.let {
            prefs.edit().putDouble(KEY_SERVICE_NOTIFIED_ODO, it).apply()
        }
        if (service.fireNotification) {
            val overdueKm = (-service.kmRemaining!!).roundToInt()
            postNotification(
                NOTIFICATION_ID_SERVICE,
                if (service.dueNow) "Service due — ${overdueKm} km overdue"
                else "Service due in ${service.kmRemaining!!.roundToInt()} km",
                "Ather recommends a check every ${MaintenancePolicy.SERVICE_INTERVAL_KM.toInt()} km. Open AtherMate and tap Mark serviced when done."
            )
        }

        var tyreLatch = MaintenancePolicy.TyreLatch(
            lastNotifiedEpochMonth = if (prefs.contains(KEY_TYRE_NOTIFIED_MONTH))
                prefs.getLong(KEY_TYRE_NOTIFIED_MONTH, 0L) else null,
            lastCheckedEpochMonth = if (prefs.contains(KEY_TYRE_CHECKED_MONTH))
                prefs.getLong(KEY_TYRE_CHECKED_MONTH, 0L) else null
        )
        val tyre = MaintenancePolicy.evaluateTyreCheck(tyreLatch, nowMs)
        tyreLatch = tyre.latch
        tyreLatch.lastNotifiedEpochMonth?.let {
            prefs.edit().putLong(KEY_TYRE_NOTIFIED_MONTH, it).apply()
        }
        if (tyre.fireNotification) {
            postNotification(
                NOTIFICATION_ID_TYRE,
                "Monthly tyre check",
                "Check pressures and tread wear on both tyres, then tap Mark checked."
            )
        }

        _snapshot.value = MaintenanceSnapshot(
            serviceInitialised = serviceLatch.nextDueOdoKm != null,
            nextDueOdoKm = serviceLatch.nextDueOdoKm,
            kmRemaining = service.kmRemaining,
            lastTyreCheckEpochMonth = tyreLatch.lastCheckedEpochMonth
        )
    }

    fun markServiced(odometerKm: Double?) {
        val latch = MaintenancePolicy.markServiced(MaintenancePolicy.ServiceLatch(
            nextDueOdoKm = prefs.getDoubleOrNull(KEY_NEXT_SERVICE_ODO),
            lastNotifiedDueOdoKm = prefs.getDoubleOrNull(KEY_SERVICE_NOTIFIED_ODO)
        ), odometerKm)
        prefs.edit()
            .putDouble(KEY_NEXT_SERVICE_ODO, latch.nextDueOdoKm!!)
            .remove(KEY_SERVICE_NOTIFIED_ODO)
            .apply()
        notificationManager.cancel(NOTIFICATION_ID_SERVICE)
        evaluate(odometerKm)
    }

    fun markTyreChecked(nowMs: Long = System.currentTimeMillis()) {
        val latch = MaintenancePolicy.markTyreChecked(MaintenancePolicy.TyreLatch(
            lastNotifiedEpochMonth = if (prefs.contains(KEY_TYRE_NOTIFIED_MONTH))
                prefs.getLong(KEY_TYRE_NOTIFIED_MONTH, 0L) else null,
            lastCheckedEpochMonth = if (prefs.contains(KEY_TYRE_CHECKED_MONTH))
                prefs.getLong(KEY_TYRE_CHECKED_MONTH, 0L) else null
        ), nowMs)
        prefs.edit()
            .putLong(KEY_TYRE_NOTIFIED_MONTH, latch.lastNotifiedEpochMonth ?: return)
            .putLong(KEY_TYRE_CHECKED_MONTH, latch.lastCheckedEpochMonth!!)
            .apply()
        notificationManager.cancel(NOTIFICATION_ID_TYRE)
        // Service latches are untouched; odometer unknown here keeps the schedule intact.
        _snapshot.value = readSnapshot()
    }

    private fun postNotification(id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(appContext, id,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charging)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(id, notification)
    }

    private fun android.content.SharedPreferences.getDoubleOrNull(key: String): Double? {
        val v = getFloat(key, Float.NaN)
        return if (v.isNaN()) null else v.toDouble()
    }

    private fun android.content.SharedPreferences.Editor.putDouble(key: String, value: Double) =
        putFloat(key, value.toFloat())

    companion object {
        const val CHANNEL_ID = "maintenance_reminders"
        const val CHANNEL_NAME = "Service Reminders"
        const val NOTIFICATION_ID_SERVICE = 12001
        const val NOTIFICATION_ID_TYRE = 12002
        private const val PREFS = "ather_maintenance"
        private const val KEY_NEXT_SERVICE_ODO = "next_service_odo"
        private const val KEY_SERVICE_NOTIFIED_ODO = "service_notified_odo"
        private const val KEY_TYRE_NOTIFIED_MONTH = "tyre_notified_month"
        private const val KEY_TYRE_CHECKED_MONTH = "tyre_checked_month"

        @Volatile
        private var INSTANCE: MaintenanceCenter? = null

        fun getInstance(context: Context): MaintenanceCenter =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MaintenanceCenter(context.applicationContext).also { INSTANCE = it }
            }
    }
}
