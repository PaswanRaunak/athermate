package io.ather.pro.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.ather.pro.domain.model.TripRecord

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Double,
    val socConsumed: Double,
    val energyConsumedWh: Double,
    val efficiencyWhPerKm: Double,
    val electricityCostInr: Double,
    val startOdoKm: Double,
    val endOdoKm: Double,
    val estimatedPackCapacityWh: Double? = null,
    val isOfficialRide: Boolean = false
) {
    fun toDomain(): TripRecord = TripRecord(
        id = id,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        distanceKm = distanceKm,
        socConsumed = socConsumed,
        energyConsumedWh = energyConsumedWh,
        efficiencyWhPerKm = efficiencyWhPerKm,
        electricityCostInr = electricityCostInr,
        startOdoKm = startOdoKm,
        endOdoKm = endOdoKm,
        estimatedPackCapacityWh = estimatedPackCapacityWh,
        isOfficialRide = isOfficialRide
    )

    companion object {
        fun fromDomain(trip: TripRecord): TripEntity = TripEntity(
            id = trip.id,
            startTimeMs = trip.startTimeMs,
            endTimeMs = trip.endTimeMs,
            distanceKm = trip.distanceKm,
            socConsumed = trip.socConsumed,
            energyConsumedWh = trip.energyConsumedWh,
            efficiencyWhPerKm = trip.efficiencyWhPerKm,
            electricityCostInr = trip.electricityCostInr,
            startOdoKm = trip.startOdoKm,
            endOdoKm = trip.endOdoKm,
            estimatedPackCapacityWh = trip.estimatedPackCapacityWh,
            isOfficialRide = trip.isOfficialRide
        )
    }
}
