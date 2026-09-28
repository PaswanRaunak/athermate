package io.ather.pro.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ather.pro.domain.model.TelemetrySample
import io.ather.pro.domain.model.TripRecord

/**
 * One-shot import of SharedPreferences JSON blobs into Room.
 * Clears legacy keys only after a successful write.
 */
object LegacyPrefsMigration {
    const val PREFERENCES_NAME = "ather_dashboard_state"
    const val KEY_TRIPS = "trip_records_v2"
    const val KEY_TELEMETRY_HISTORY = "telemetry_history_v2"
    const val KEY_BASELINE_ODO = "trip_baseline_odo"
    const val KEY_BASELINE_SOC = "trip_baseline_soc"
    const val KEY_BASELINE_TIMESTAMP = "trip_baseline_timestamp"
    const val META_MIGRATED = "legacy_prefs_migrated_v1"

    private val gson = Gson()
    private val tripsType = object : TypeToken<List<TripRecord>>() {}.type
    private val samplesType = object : TypeToken<List<TelemetrySample>>() {}.type

    fun parseTripsJson(json: String?): List<TripRecord> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<TripRecord>>(json, tripsType).orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.startTimeMs > 0L }
    }

    fun parseTelemetryHistoryJson(json: String?): List<TelemetrySample> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<TelemetrySample>>(json, samplesType).orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.timestamp > 0L && it.speedKmh.isFinite() && it.batterySoc in 0.0..100.0 }
            .sortedBy(TelemetrySample::timestamp)
    }

    fun parseBaselineFromPrefs(
        hasOdo: Boolean,
        hasSoc: Boolean,
        odoBits: Long,
        socBits: Long,
        timestampMs: Long
    ): TripBaseline? {
        if (!hasOdo || !hasSoc) return null
        val odometer = java.lang.Double.longBitsToDouble(odoBits)
        val soc = java.lang.Double.longBitsToDouble(socBits)
        return if (odometer.isFinite() && odometer >= 0.0 && soc in 0.0..100.0 && timestampMs > 0L) {
            TripBaseline(odometer, soc, timestampMs)
        } else {
            null
        }
    }

    fun migrateIfNeeded(context: Context, dao: DashboardDao): Boolean {
        if (dao.getMeta(META_MIGRATED) == "1") return false

        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )

        val trips = parseTripsJson(preferences.getString(KEY_TRIPS, null))
        val history = parseTelemetryHistoryJson(preferences.getString(KEY_TELEMETRY_HISTORY, null))
        val baseline = parseBaselineFromPrefs(
            hasOdo = preferences.contains(KEY_BASELINE_ODO),
            hasSoc = preferences.contains(KEY_BASELINE_SOC),
            odoBits = preferences.getLong(KEY_BASELINE_ODO, 0L),
            socBits = preferences.getLong(KEY_BASELINE_SOC, 0L),
            timestampMs = preferences.getLong(KEY_BASELINE_TIMESTAMP, 0L)
        )

        if (trips.isNotEmpty()) {
            dao.replaceTrips(trips.take(DashboardLocalStore.MAX_TRIPS).map(TripEntity::fromDomain))
        }
        if (history.isNotEmpty()) {
            dao.replaceTelemetryHistory(
                history.takeLast(DashboardLocalStore.MAX_PERSISTED_SAMPLES)
                    .map(TelemetrySampleEntity::fromDomain)
            )
        }
        if (baseline != null && dao.loadTripBaseline() == null) {
            dao.saveTripBaseline(TripBaselineEntity.fromDomain(baseline))
        }

        dao.putMeta(MetaEntity(META_MIGRATED, "1"))

        preferences.edit()
            .remove(KEY_TRIPS)
            .remove(KEY_TELEMETRY_HISTORY)
            .remove(KEY_BASELINE_ODO)
            .remove(KEY_BASELINE_SOC)
            .remove(KEY_BASELINE_TIMESTAMP)
            .apply()

        return true
    }
}
