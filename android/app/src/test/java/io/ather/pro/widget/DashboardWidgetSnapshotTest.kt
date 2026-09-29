package io.ather.pro.widget

import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ModeRange
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.model.ScooterSettings
import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.*
import org.junit.Test

class DashboardWidgetSnapshotTest {
    @Test fun widgetUsesCurrentChargeAndOnlySupportedModes() {
        val snapshot = DashboardWidgetSnapshot.fromDashboard(ScooterDashboardState(
            settings = ScooterSettings(selectedModel = ScooterModel.ATHER_450X_3_7),
            telemetry = ScooterTelemetry(batterySoc = 30.0, mode = "ride", modeRanges = mapOf(
                "Eco" to ModeRange(predictedRangeKm = 35.0),
                "Ride" to ModeRange(predictedRangeKm = 30.0),
                "WarpPlus" to ModeRange(predictedRangeKm = 20.0)))))
        assertEquals("30%", snapshot.socText)
        assertEquals("Range at 30% battery", snapshot.modesLabel)
        assertEquals(listOf("Eco", "Ride"), snapshot.modeRanges.map { it.name })
        assertEquals(listOf(35.0, 30.0), snapshot.modeRanges.map { it.km })
        assertEquals("Ride", snapshot.currentMode)
        assertEquals(30.0, snapshot.rangeKm!!, 0.0)
        assertFalse(snapshot.modesText.contains("Warp"))
    }

    @Test fun freshGpsDoesNotMakeAnOldBatteryReadingLookLive() {
        val snapshot = DashboardWidgetSnapshot.fromDashboard(ScooterDashboardState(
            telemetry = ScooterTelemetry(batterySoc = 30.0), connection = ConnectionStatus.CONNECTED,
            lastUpdated = 180_000, gpsUpdatedAt = 180_000, batteryUpdatedAt = 10_000))
        assertEquals(10_000L, snapshot.updatedAtMs)
    }

    @Test fun missingAndInvalidSocDoNotBecomeZeroOrAnInventedEstimate() {
        for (soc in listOf(null, Double.NaN, -1.0, 101.0)) {
            val snapshot = DashboardWidgetSnapshot.fromDashboard(ScooterDashboardState(telemetry = ScooterTelemetry(batterySoc = soc)))
            assertNull(snapshot.socPercent)
            assertEquals("—", snapshot.socText)
            assertEquals("Range by mode", snapshot.modesLabel)
            assertTrue(snapshot.modeRanges.isEmpty())
            assertNull(snapshot.rangeKm)
        }
    }

    @Test fun widgetAndDashboardUseSame101KmLiveSmartEcoAt79Percent() {
        val telemetry = ScooterTelemetry(batterySoc = 79.0, rangeKm = 101.0, mode = "SmartEco", modeRanges = mapOf(
            "SmartEco" to ModeRange(predictedRangeKm = 130.0),
            "Ride" to ModeRange(predictedRangeKm = 105.0)))
        val snapshot = DashboardWidgetSnapshot.fromDashboard(ScooterDashboardState(telemetry = telemetry))
        assertEquals("Range at 79% battery", snapshot.modesLabel)
        assertEquals("SmartEco 101 km · Ride 82 km", snapshot.modesText)
    }
}
