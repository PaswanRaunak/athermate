package io.ather.pro.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.ather.pro.appContainer
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.service.ChargingCheckWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

object DashboardWidgetUpdater {
    private var previousSnapshot: DashboardWidgetSnapshot? = null
    private var previousStale: Boolean? = null
    @Synchronized
    fun publish(context: Context, state: ScooterDashboardState, limit: ChargeLimitController.Snapshot = ChargeLimitController.Snapshot()) {
        val appContext = context.applicationContext
        val incoming = DashboardWidgetSnapshot.fromDashboard(state, limit)
        // Limiter changes must reach the widget even while waiting for a new scooter reading.
        val snapshot = if (state.telemetry == null && state.lastUpdated == null) {
            val saved = previousSnapshot ?: DashboardWidgetSnapshot.load(appContext)
            saved.copy(connectionLabel = incoming.connectionLabel, limitPercent = incoming.limitPercent,
                chargeLabel = incoming.chargeLabel, estimatedStopAtMs = incoming.estimatedStopAtMs)
        } else incoming
        val stale = System.currentTimeMillis() - snapshot.updatedAtMs > 60_000L
        if (snapshot == previousSnapshot && stale == previousStale) return
        previousSnapshot = snapshot
        previousStale = stale
        DashboardWidgetSnapshot.save(appContext, snapshot)
        refreshAll(appContext)
    }

    @Synchronized
    fun clear(context: Context) {
        previousSnapshot = null
        previousStale = null
        DashboardWidgetSnapshot.save(context, DashboardWidgetSnapshot())
        refreshAll(context)
    }

    /** Manual refresh shared by every widget: live connection refreshes directly, else a WorkManager check. */
    fun enqueueManualRefresh(context: Context) {
        val container = context.applicationContext.appContainer
        if (container.monitoring.state.value.running) {
            container.repository.refresh()
        } else {
            val request = OneTimeWorkRequestBuilder<ChargingCheckWorker>()
                .setInputData(workDataOf(ChargingCheckWorker.MANUAL_REFRESH to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("scootscribe-widget-refresh", ExistingWorkPolicy.KEEP, request)
        }
    }

    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        for (provider in listOf(ScooterStatusWidgetProvider::class.java, ChargingWidgetProvider::class.java)) {
            val ids = manager.getAppWidgetIds(ComponentName(appContext, provider))
            if (ids.isEmpty()) continue
            for (id in ids) {
                when (provider) {
                    ChargingWidgetProvider::class.java ->
                        ChargingWidgetProvider.updateAppWidget(appContext, manager, id)
                    else -> ScooterStatusWidgetProvider.updateAppWidget(appContext, manager, id)
                }
            }
        }
    }
}
