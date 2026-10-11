package io.ather.pro.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "charging_sessions")
data class ChargingSessionEntity(
    @PrimaryKey val startedAtMs: Long,
    val endedAtMs: Long,
    val startSoc: Double?,
    val endSoc: Double?,
    val energyKWh: Double?,
    val costInr: Double?
)
