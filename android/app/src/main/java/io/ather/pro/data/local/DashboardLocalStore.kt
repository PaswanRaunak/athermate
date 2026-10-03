package io.ather.pro.data.local

import android.content.Context
import io.ather.pro.domain.battery.RideSample
import io.ather.pro.domain.battery.RideHistory
import io.ather.pro.domain.model.TelemetrySample
import io.ather.pro.domain.model.TripRecord

data class TripBaseline(
    val odometerKm: Double,
    val batterySoc: Double,
    val timestampMs: Long
)

/** Room-backed persistence for ride snapshots, graph history, and trip baseline. */
class DashboardLocalStore(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AtherDatabase.getInstance(appContext).dashboardDao()
    private var persistedHistoryAt = 0L

    init {
        LegacyPrefsMigration.migrateIfNeeded(appContext, dao)
    }

    fun loadTrips(): List<TripRecord> =
        dao.loadTrips(MAX_TRIPS).map(TripEntity::toDomain)

    fun saveTrips(trips: List<TripRecord>) {
        dao.replaceTrips(
            trips.take(MAX_TRIPS).map(TripEntity::fromDomain)
        )
    }

    fun loadTelemetryHistory(): List<TelemetrySample> =
        dao.loadTelemetryHistory()
            .map(TelemetrySampleEntity::toDomain)
            .filter { it.timestamp > 0L && it.speedKmh.isFinite() && it.batterySoc in 0.0..100.0 }
            .sortedBy(TelemetrySample::timestamp)
            .takeLast(MAX_PERSISTED_SAMPLES).also { persistedHistoryAt = it.lastOrNull()?.timestamp ?: 0L }

    fun saveTelemetryHistory(samples: List<TelemetrySample>) {
        val sanitized = samples
            .filter { it.timestamp > 0L && it.speedKmh.isFinite() && it.batterySoc in 0.0..100.0 }
            .sortedBy(TelemetrySample::timestamp)
            .takeLast(MAX_PERSISTED_SAMPLES)
            .map(TelemetrySampleEntity::fromDomain)
        if (sanitized.isEmpty()) return
        val newSamples = sanitized.filter { it.timestamp > persistedHistoryAt }
        if (newSamples.isEmpty()) return
        dao.appendTelemetryHistory(newSamples, sanitized.last().timestamp - io.ather.pro.domain.battery.BatteryHistory.RETENTION_MS)
        persistedHistoryAt = sanitized.last().timestamp
    }

    fun loadRideHistory(): List<RideSample> = dao.loadRideHistory().map(RideSampleEntity::toDomain).takeLast(RideHistory.MAX_SAMPLES)

    fun saveRideHistory(samples: List<RideSample>) = dao.saveRideHistory(samples.takeLast(RideHistory.MAX_SAMPLES).map(RideSampleEntity::fromDomain))

    fun loadTripBaseline(): TripBaseline? = dao.loadTripBaseline()?.toDomain()

    fun saveTripBaseline(baseline: TripBaseline) {
        if (!baseline.odometerKm.isFinite() || baseline.odometerKm < 0.0) return
        if (baseline.batterySoc !in 0.0..100.0 || baseline.timestampMs <= 0L) return
        dao.saveTripBaseline(TripBaselineEntity.fromDomain(baseline))
    }

    companion object {
        const val MAX_TRIPS = 100
        const val MAX_PERSISTED_SAMPLES = io.ather.pro.domain.battery.BatteryHistory.MAX_SAMPLES
    }
}
