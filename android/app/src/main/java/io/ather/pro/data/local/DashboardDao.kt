package io.ather.pro.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DashboardDao {
    @Query("SELECT * FROM trips ORDER BY startTimeMs DESC LIMIT :limit")
    fun loadTrips(limit: Int): List<TripEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTrips(trips: List<TripEntity>)

    @Query("DELETE FROM trips")
    fun clearTrips()

    @Transaction
    fun replaceTrips(trips: List<TripEntity>) {
        clearTrips()
        if (trips.isNotEmpty()) {
            upsertTrips(trips)
        }
    }

    @Query("SELECT * FROM telemetry_history ORDER BY timestamp ASC")
    fun loadTelemetryHistory(): List<TelemetrySampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTelemetrySamples(samples: List<TelemetrySampleEntity>)

    @Query("DELETE FROM telemetry_history WHERE timestamp < :oldest")
    fun pruneTelemetryHistory(oldest: Long)

    @Transaction
    fun appendTelemetryHistory(samples: List<TelemetrySampleEntity>, oldest: Long) {
        upsertTelemetrySamples(samples)
        pruneTelemetryHistory(oldest)
    }

    @Query("DELETE FROM telemetry_history")
    fun clearTelemetryHistory()

    @Transaction
    fun replaceTelemetryHistory(samples: List<TelemetrySampleEntity>) {
        clearTelemetryHistory()
        if (samples.isNotEmpty()) {
            upsertTelemetrySamples(samples)
        }
    }

    @Query("SELECT * FROM ride_history ORDER BY timestamp ASC")
    fun loadRideHistory(): List<RideSampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRideHistory(samples: List<RideSampleEntity>)

    @Query("DELETE FROM ride_history WHERE timestamp < :oldest")
    fun pruneRideHistory(oldest: Long)

    @Transaction
    fun saveRideHistory(samples: List<RideSampleEntity>) {
        if (samples.isEmpty()) return
        upsertRideHistory(samples)
        pruneRideHistory(samples.first().timestamp)
    }

    @Query("SELECT * FROM trip_baseline WHERE id = :id LIMIT 1")
    fun loadTripBaseline(id: Int = TripBaselineEntity.SINGLETON_ID): TripBaselineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveTripBaseline(baseline: TripBaselineEntity)

    @Query("SELECT value FROM meta WHERE key = :key LIMIT 1")
    fun getMeta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putMeta(meta: MetaEntity)
}
