package io.ather.pro.domain.analytics

import io.ather.pro.domain.model.TripRecord

/**
 * Analytics domain models. Scorecard fields mirror Ather's vehicle-health report
 * and must never be presented as BMS State of Health.
 */

data class AnalyticsTrip(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Double,
    val energyConsumedWh: Double,
    val efficiencyWhPerKm: Double,
    val electricityCostInr: Double,
    val isOfficialRide: Boolean,
    /** Ather riding mode when known; Room TripRecord does not carry this. */
    val mode: String? = null
) {
    companion object {
        fun fromTripRecord(trip: TripRecord, mode: String? = null): AnalyticsTrip = AnalyticsTrip(
            id = trip.id,
            startTimeMs = trip.startTimeMs,
            endTimeMs = trip.endTimeMs,
            distanceKm = trip.distanceKm,
            energyConsumedWh = trip.energyConsumedWh,
            efficiencyWhPerKm = trip.efficiencyWhPerKm,
            electricityCostInr = trip.electricityCostInr,
            isOfficialRide = trip.isOfficialRide,
            mode = mode
        )
    }
}

data class AnalyticsTotals(
    val tripCount: Int,
    val totalDistanceKm: Double,
    val totalEnergyWh: Double,
    val totalCostInr: Double,
    val averageEfficiencyWhPerKm: Double?,
    val officialTripCount: Int,
    val localTripCount: Int,
    val sourceLabel: String
)

data class ChartPoint(
    val key: String,
    val label: String,
    val value: Double,
    val sortKey: Long = 0L
)

data class ChartSeries(
    val id: String,
    val title: String,
    val unit: String,
    val sourceLabel: String,
    val points: List<ChartPoint>
) {
    val isEmpty: Boolean get() = points.isEmpty()
}

data class RideAnalyticsSnapshot(
    val totals: AnalyticsTotals,
    val weeklyDistanceKm: ChartSeries,
    val weeklyEnergyWh: ChartSeries,
    val weeklyCostInr: ChartSeries,
    val weeklyEfficiencyWhPerKm: ChartSeries,
    val timeOfDayDistanceKm: ChartSeries,
    val modeDistanceKm: ChartSeries
)

data class VehicleHealthTag(
    val text: String?,
    val textColor: String? = null,
    val backgroundColor: String? = null
)

data class VehicleHealthOverall(
    val score: Float?,
    val label: String?,
    val maxScore: Int?,
    val colorCode: String?
)

data class VehicleHealthComponent(
    val id: String?,
    val name: String?,
    val score: Float?,
    val maxScore: Int?,
    val tag: VehicleHealthTag?,
    val displayType: String?
)

data class VehicleWearItem(
    val id: String?,
    val name: String?,
    val lifePercent: Int?,
    val currentKms: Int?,
    val remainingKms: Int?,
    val tag: VehicleHealthTag?,
    val description: VehicleHealthTag?
)

data class VehicleHealthResale(
    val currency: String?,
    val disclaimer: String?,
    val minValue: Int?,
    val maxValue: Int?,
    val displayText: String?
)

data class VehicleHealthVehicleInfo(
    val id: String?,
    val name: String?,
    val registrationMasked: String?,
    val ageYears: String?,
    val odoKms: Int?,
    val odoFormatted: String?
)

data class VehicleHealthMeta(
    val lastUpdated: String?,
    val refreshNote: String?
)

/**
 * Ather True Health Scorecard payload. Not BMS SoH.
 */
data class VehicleHealthReport(
    val vehicle: VehicleHealthVehicleInfo?,
    val overall: VehicleHealthOverall?,
    val components: List<VehicleHealthComponent>,
    val wearAndTear: List<VehicleWearItem>,
    val resale: VehicleHealthResale?,
    val meta: VehicleHealthMeta?,
    val sourceLabel: String = "Ather vehicle-health report (not BMS SoH)"
)

sealed class ScorecardState {
    data object Loading : ScorecardState()
    /** Always render a card; [reason] explains why Ather did not supply a usable report. */
    data class Unavailable(
        val reason: String = "Ather supplied no vehicle-health report."
    ) : ScorecardState()
    data class Available(val report: VehicleHealthReport) : ScorecardState()
    data class Error(val message: String) : ScorecardState()
}
