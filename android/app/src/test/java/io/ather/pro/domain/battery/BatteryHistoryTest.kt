package io.ather.pro.domain.battery

import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TelemetrySample
import org.junit.Assert.*
import org.junit.Test

class BatteryHistoryTest {
    private fun point(time: Long, soc: Double) = TelemetrySample(time, 0.0, soc, "Unknown")

    @Test fun continuousFastPacketsStillProduceOneSamplePerSecond() {
        var history = emptyList<TelemetrySample>()
        for (i in 0..30) {
            history = BatteryHistory.record(history, ScooterTelemetry(batterySoc = 80.0 - i / 10.0),
                10_000L + i * 100)
        }
        assertEquals(listOf(10_000L, 11_000L, 12_000L, 13_000L), history.map { it.timestamp })
        assertEquals(77.0, history.last().batterySoc, 0.001)
    }

    @Test fun batteryReportsDoNotRequireRidingModeAndUnrelatedPacketsDoNotAddPoints() {
        val history = BatteryHistory.record(emptyList(), ScooterTelemetry(batterySoc = 65.0), 10_000L)
        assertEquals(1, history.size)
        assertSame(history, BatteryHistory.record(history, ScooterTelemetry(chargerConnected = true), 12_000L))
        assertSame(history, BatteryHistory.record(history, ScooterTelemetry(batterySoc = Double.NaN), 12_000L))
        assertSame(history, BatteryHistory.record(history, ScooterTelemetry(batterySoc = 101.0), 12_000L))
    }

    @Test fun selectingBetweenIrregularReadingsUsesTimeNotListIndex() {
        val points = listOf(point(1_000, 70.0), point(2_000, 71.0), point(9_000, 75.0))
        assertEquals(points[1], BatteryHistory.nearest(points, 1_000, 9_000, 0.3f))
        assertEquals(points[2], BatteryHistory.nearest(points, 1_000, 9_000, 0.75f))
        assertEquals(points.first(), BatteryHistory.nearest(points, 1_000, 9_000, -1f))
        assertEquals(points.last(), BatteryHistory.nearest(points, 1_000, 9_000, 2f))
    }

    @Test fun staleWindowDoesNotInventCurrentPointsAndSinglePointCanBeSelected() {
        val point = point(1_000, 83.2)
        assertTrue(BatteryHistory.visible(listOf(point), 100_000, 60_000).isEmpty())
        assertEquals(listOf(point), BatteryHistory.visible(listOf(point), 100_000, null))
        assertEquals(point, BatteryHistory.nearest(listOf(point), 1_000, 1_000, 0.5f))
        assertNull(BatteryHistory.nearest(emptyList(), 1_000, 2_000, 0.5f))
    }
}
