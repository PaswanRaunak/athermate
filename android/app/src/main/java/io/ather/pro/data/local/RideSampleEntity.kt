package io.ather.pro.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.ather.pro.domain.battery.RideSample

@Entity(tableName = "ride_history")
data class RideSampleEntity(@PrimaryKey val timestamp: Long, val speedKmh: Double?, val odometerKm: Double?, val rangeKm: Double?) {
    fun toDomain() = RideSample(timestamp, speedKmh, odometerKm, rangeKm)
    companion object {
        fun fromDomain(sample: RideSample) = RideSampleEntity(sample.timestamp, sample.speedKmh, sample.odometerKm, sample.rangeKm)
    }
}
