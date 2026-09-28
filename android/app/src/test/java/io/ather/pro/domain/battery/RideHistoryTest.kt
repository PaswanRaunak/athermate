package io.ather.pro.domain.battery

import io.ather.pro.domain.model.GpsData
import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.*
import org.junit.Test

class RideHistoryTest {
    @Test fun sparseGpsSamplesDoNotFabricateBatteryOrStandstill() {
        val history = RideHistory.record(emptyList(), ScooterTelemetry(gps = GpsData(speed = 36.0)), 1_000)
        val updated = RideHistory.record(history, ScooterTelemetry(odoKm = 105.0), 2_000)
        assertEquals(1, RideHistory.series(updated, RideMetric.SPEED, 0, 3_000).size)
        assertNull(updated.last().speedKmh)
    }
    @Test fun distanceUsesMeasuredOdometerAndResetsAtWindowStart() {
        val history = listOf(RideSample(1_000, odometerKm = 100.0), RideSample(2_000, odometerKm = 101.5), RideSample(3_000, odometerKm = 103.0))
        assertEquals(listOf(0.0, 1.5, 3.0), RideHistory.series(history, RideMetric.DISTANCE, 0, 3_000).map { it.value })
        assertEquals(listOf(0.0, 1.5), RideHistory.series(history, RideMetric.DISTANCE, 2_000, 3_000).map { it.value })
    }
    @Test fun invalidAndOutOfOrderReportsAreIgnored() {
        val original = listOf(RideSample(2_000, speedKmh = 20.0))
        assertEquals(original, RideHistory.record(original, ScooterTelemetry(gps = GpsData(speed = Double.NaN)), 3_000))
        assertEquals(original, RideHistory.record(original, ScooterTelemetry(gps = GpsData(speed = 35.0)), 1_000))
    }
    @Test fun sameSecondMergesIndependentFieldsWithoutInventingSamples() {
        val history = RideHistory.record(emptyList(), ScooterTelemetry(gps = GpsData(speed = 22.0)), 1_000)
        val updated = RideHistory.record(history, ScooterTelemetry(odoKm = 1_100.0), 1_500)
        assertEquals(1, updated.size)
        assertEquals(22.0, updated.single().speedKmh!!, 0.0)
        assertEquals(1_100.0, updated.single().odometerKm!!, 0.0)
    }
}
