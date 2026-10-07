package io.ather.pro.util

import android.Manifest
import android.app.Notification
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
import io.ather.pro.domain.model.ScooterTelemetry
import kotlin.math.roundToInt

class ChargingNotificationManager private constructor(private val context: Context) {

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val latchPrefs = appContext.getSharedPreferences(LATCH_PREFS, Context.MODE_PRIVATE)

    private var chargingLatch = VehicleAlertEvaluator.ChargingLatch(
        sessionActive = latchPrefs.getBoolean(KEY_SESSION_ACTIVE, false),
        notified80 = latchPrefs.getBoolean(KEY_NOTIFIED_80, false),
        notified100 = latchPrefs.getBoolean(KEY_NOTIFIED_100, false)
    )
    private var tpmsLatch = VehicleAlertEvaluator.TpmsLatch(
        frontLowLatched = latchPrefs.getBoolean(KEY_TPMS_FRONT_LOW, false),
        rearLowLatched = latchPrefs.getBoolean(KEY_TPMS_REAR_LOW, false)
    )

    companion object {
        const val CHANNEL_PROGRESS_ID = "charging_progress"
        const val CHANNEL_PROGRESS_NAME = "Charging Progress"
        const val CHANNEL_ALERTS_ID = "charging_alerts"
        const val CHANNEL_ALERTS_NAME = "Charging Alerts"
        const val CHANNEL_TPMS_ID = "tpms_alerts"
        const val CHANNEL_TPMS_NAME = "Tyre Pressure Alerts"
        const val CHANNEL_CHARGE_LIMIT_ID = "charge_limit"
        const val CHANNEL_CHARGE_LIMIT_NAME = "Charge Limit Monitor"

        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_ALERT_80 = 8001
        const val NOTIFICATION_ID_ALERT_100 = 10001
        const val NOTIFICATION_ID_TPMS_FRONT = 9001
        const val NOTIFICATION_ID_TPMS_REAR = 9002
        const val NOTIFICATION_ID_CHARGE_LIMIT = 1101

        private const val LATCH_PREFS = "ather_alert_latches"
        private const val KEY_SESSION_ACTIVE = "charge_session_active"
        private const val KEY_NOTIFIED_80 = "charge_notified_80"
        private const val KEY_NOTIFIED_100 = "charge_notified_100"
        private const val KEY_TPMS_FRONT_LOW = "tpms_front_low"
        private const val KEY_TPMS_REAR_LOW = "tpms_rear_low"

        @Volatile
        private var INSTANCE: ChargingNotificationManager? = null

        fun getInstance(context: Context): ChargingNotificationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChargingNotificationManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    init {
        createNotificationChannels()
    }

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS_ID,
                CHANNEL_PROGRESS_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing charging progress and ETA status"
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                CHANNEL_ALERTS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Milestone alerts for 80% and 100% charge completion"
                enableVibration(true)
                setShowBadge(true)
            }

            val tpmsChannel = NotificationChannel(
                CHANNEL_TPMS_ID,
                CHANNEL_TPMS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Low tyre pressure alerts when TPMS hardware reports values"
                enableVibration(true)
                setShowBadge(true)
            }

            val chargeLimitChannel = NotificationChannel(
                CHANNEL_CHARGE_LIMIT_ID,
                CHANNEL_CHARGE_LIMIT_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent monitor for phone-app charge limit automation"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(alertsChannel)
            notificationManager.createNotificationChannel(tpmsChannel)
            notificationManager.createNotificationChannel(chargeLimitChannel)
        }
    }

    fun buildChargeLimitNotification(
        percent: Int,
        statusLabel: String,
        detail: String? = null
    ): Notification {
        val pendingIntent = createDashboardPendingIntent(NOTIFICATION_ID_CHARGE_LIMIT)
        val text = detail?.takeIf { it.isNotBlank() }
            ?: "Phone-app stop at $percent%. Offline/force-stop/network loss prevents enforcement."
        return NotificationCompat.Builder(appContext, CHANNEL_CHARGE_LIMIT_ID)
            .setSmallIcon(R.drawable.ic_stat_charging)
            .setContentTitle("Charge limit $percent% — $statusLabel")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
    }

    fun cancelChargeLimitNotification() {
        notificationManager.cancel(NOTIFICATION_ID_CHARGE_LIMIT)
    }

    fun update(telemetry: ScooterTelemetry, showProgress: Boolean = true) {
        val (isConnected, isCharging) = VehicleAlertEvaluator.isChargingConnected(telemetry)
        val chargeDecision = VehicleAlertEvaluator.evaluateCharging(
            previous = chargingLatch,
            isConnected = isConnected,
            isCharging = isCharging,
            soc = telemetry.batterySoc
        )
        chargingLatch = chargeDecision.latch
        persistChargingLatch()

        if (showProgress && chargeDecision.showProgress) {
            telemetry.batterySoc?.let { soc ->
                postProgressNotification(
                    soc,
                    telemetry.timeToEightyChargeMin,
                    telemetry.timeToFullChargeMin
                )
            }
        } else {
            cancelProgressNotification()
        }

        if (chargeDecision.fire80) postAlert80Notification()
        if (chargeDecision.fire100) postAlert100Notification()

        val tpmsDecision = VehicleAlertEvaluator.evaluateTpms(tpmsLatch, telemetry.tpms)
        tpmsLatch = tpmsDecision.latch
        persistTpmsLatch()
        if (tpmsDecision.fireFrontLow) {
            postTpmsLowNotification(
                NOTIFICATION_ID_TPMS_FRONT,
                "Front tyre pressure low",
                tpmsDecision.frontPsi
            )
        }
        if (tpmsDecision.fireRearLow) {
            postTpmsLowNotification(
                NOTIFICATION_ID_TPMS_REAR,
                "Rear tyre pressure low",
                tpmsDecision.rearPsi
            )
        }
    }

    fun buildProgressNotification(
        soc: Double? = null,
        timeToEighty: Double? = null,
        timeToFull: Double? = null
    ): Notification {
        val socInt = soc?.roundToInt()?.coerceIn(0, 100)
        val title = if (socInt != null) "AtherMate Charging — $socInt%" else "AtherMate Charging"
        val contentText = when {
            socInt != null && socInt < 80 && timeToEighty != null && timeToEighty > 0.0 -> {
                val eta80 = timeToEighty.roundToInt()
                val fullText = timeToFull?.takeIf { it > 0.0 }?.let { " · Full in ${it.roundToInt()}m" } ?: ""
                "ETA to 80%: ${eta80}m$fullText"
            }
            socInt != null && timeToFull != null && timeToFull > 0.0 -> {
                "ETA to Full: ${timeToFull.roundToInt()}m"
            }
            socInt != null -> "Battery Level: $socInt%"
            else -> "Monitoring charging session..."
        }

        val pendingIntent = createDashboardPendingIntent(NOTIFICATION_ID_PROGRESS)

        return NotificationCompat.Builder(appContext, CHANNEL_PROGRESS_ID)
            .setSmallIcon(R.drawable.ic_stat_charging)
            .setColor(0xFF4ADE80.toInt())
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(100, socInt ?: 0, socInt == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
    }

    private fun postProgressNotification(
        soc: Double,
        timeToEighty: Double?,
        timeToFull: Double?
    ) {
        if (!hasNotificationPermission()) return
        val notification = buildProgressNotification(soc, timeToEighty, timeToFull)
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    private fun postAlert80Notification() {
        if (!hasNotificationPermission()) return

        val pendingIntent = createDashboardPendingIntent(NOTIFICATION_ID_ALERT_80)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ALERTS_ID)
            .setSmallIcon(R.drawable.ic_stat_charging)
            .setContentTitle("AtherMate — 80% Charged")
            .setContentText("Optimal battery health limit reached (80%).")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_ALERT_80, notification)
    }

    private fun postAlert100Notification() {
        if (!hasNotificationPermission()) return

        val pendingIntent = createDashboardPendingIntent(NOTIFICATION_ID_ALERT_100)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ALERTS_ID)
            .setSmallIcon(R.drawable.ic_stat_charging)
            .setContentTitle("AtherMate — Fully Charged")
            .setContentText("Battery reached 100%. Vehicle is ready to ride.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_ALERT_100, notification)
    }

    private fun postTpmsLowNotification(id: Int, title: String, psi: Double?) {
        if (!hasNotificationPermission()) return
        val psiText = psi?.let { String.format(java.util.Locale.US, "%.0f psi", it) } ?: "below threshold"
        val pendingIntent = createDashboardPendingIntent(id)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_TPMS_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText("Reported $psiText (threshold ${VehicleAlertEvaluator.TPMS_LOW_PSI.toInt()} psi).")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(id, notification)
    }

    fun cancelProgressNotification() {
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)
    }

    fun cancelAll() {
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)
        notificationManager.cancel(NOTIFICATION_ID_ALERT_80)
        notificationManager.cancel(NOTIFICATION_ID_ALERT_100)
        notificationManager.cancel(NOTIFICATION_ID_TPMS_FRONT)
        notificationManager.cancel(NOTIFICATION_ID_TPMS_REAR)
        notificationManager.cancel(NOTIFICATION_ID_CHARGE_LIMIT)
    }

    private fun persistChargingLatch() {
        latchPrefs.edit()
            .putBoolean(KEY_SESSION_ACTIVE, chargingLatch.sessionActive)
            .putBoolean(KEY_NOTIFIED_80, chargingLatch.notified80)
            .putBoolean(KEY_NOTIFIED_100, chargingLatch.notified100)
            .apply()
    }

    private fun persistTpmsLatch() {
        latchPrefs.edit()
            .putBoolean(KEY_TPMS_FRONT_LOW, tpmsLatch.frontLowLatched)
            .putBoolean(KEY_TPMS_REAR_LOW, tpmsLatch.rearLowLatched)
            .apply()
    }

    private fun createDashboardPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
