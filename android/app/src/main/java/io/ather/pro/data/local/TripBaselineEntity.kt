package io.ather.pro.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trip_baseline")
data class TripBaselineEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val odometerKm: Double,
    val batterySoc: Double,
    val timestampMs: Long
) {
    fun toDomain(): TripBaseline = TripBaseline(odometerKm, batterySoc, timestampMs)

    companion object {
        const val SINGLETON_ID = 1

        fun fromDomain(baseline: TripBaseline): TripBaselineEntity = TripBaselineEntity(
            odometerKm = baseline.odometerKm,
            batterySoc = baseline.batterySoc,
            timestampMs = baseline.timestampMs
        )
    }
}
