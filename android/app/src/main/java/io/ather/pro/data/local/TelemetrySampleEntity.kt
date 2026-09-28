package io.ather.pro.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.ather.pro.domain.model.TelemetrySample

@Entity(tableName = "telemetry_history")
data class TelemetrySampleEntity(
    @PrimaryKey val timestamp: Long,
    val speedKmh: Double,
    val batterySoc: Double,
    val mode: String
) {
    fun toDomain(): TelemetrySample = TelemetrySample(
        timestamp = timestamp,
        speedKmh = speedKmh,
        batterySoc = batterySoc,
        mode = mode
    )

    companion object {
        fun fromDomain(sample: TelemetrySample): TelemetrySampleEntity = TelemetrySampleEntity(
            timestamp = sample.timestamp,
            speedKmh = sample.speedKmh,
            batterySoc = sample.batterySoc,
            mode = sample.mode
        )
    }
}
