package io.ather.pro.widget

import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ModeRange
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.model.ScooterSettings
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.VehicleProfile
import org.junit.Assert.*
import org.junit.Test

class DashboardWidgetSnapshotTest {
    private val modeTelemetry = ScooterTelemetry(mode = "Ride", modeRanges = mapOf(
        "SmartEco" to ModeRange(rawRangeKm = 40.0),
        "Eco" to ModeRange(rawRangeKm = 36.0),
        "Ride" to ModeRange(rawRangeKm = 32.0),
        "Sport" to ModeRange(rawRangeKm = 28.0),
        "Warp" to ModeRange(rawRangeKm = 24.0),
        "WarpPlus" to ModeRange(rawRangeKm = 20.0)
    ))

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

    @Test fun detected450SHidesWarpOnTheWidgetEvenWhenSavedModelIs450X() {
        val snapshot = DashboardWidgetSnapshot.fromDashboard(ScooterDashboardState(
            settings = ScooterSettings(selectedModel = ScooterModel.ATHER_450X_3_7),
            vehicleProfile = VehicleProfile(modelType = "450S"),
            telemetry = modeTelemetry))
        assertEquals(listOf("SmartEco", "Eco", "Ride", "Sport"), snapshot.modeRanges.map { it.name })
        assertFalse(snapshot.modesText.contains("Warp"))
        assertEquals("Ride", snapshot.currentMode)
    }

    @Test fun unresolvedProfileDoesNotShowWarpPlusOnTheWidget() {
        val snapshot = DashboardWidgetSnapshot.fromDashboard(ScooterDashboardState(
            settings = ScooterSettings(selectedModel = ScooterModel.ATHER_450X_3_7),
            vehicleProfile = VehicleProfile(modelType = "450X"),
            telemetry = modeTelemetry))
        assertEquals(listOf("SmartEco", "Eco", "Ride", "Sport", "Warp"), snapshot.modeRanges.map { it.name })
    }

    @Test fun manualModelRemainsOnTheWidgetWhenProfileCannotNameTheScooter() {
        val snapshot = DashboardWidgetSnapshot.fromDashboard(ScooterDashboardState(
            settings = ScooterSettings(selectedModel = ScooterModel.ATHER_450S),
            vehicleProfile = VehicleProfile(modelType = "450X"),
            telemetry = modeTelemetry))
        assertEquals(listOf("SmartEco", "Eco", "Ride", "Sport"), snapshot.modeRanges.map { it.name })
        assertFalse(snapshot.modesText.contains("Warp"))
    }

    @Test fun warpCurrentModeIsHiddenWhenTheDetectedModelDoesNotSupportIt() {
        val snapshot = DashboardWidgetSnapshot.fromDashboard(ScooterDashboardState(
            settings = ScooterSettings(selectedModel = ScooterModel.ATHER_450X_3_7),
            vehicleProfile = VehicleProfile(modelType = "450S"),
            telemetry = modeTelemetry.copy(mode = "Warp")))
        assertNull(snapshot.currentMode)
        assertEquals(listOf("SmartEco", "Eco", "Ride", "Sport"), snapshot.modeRanges.map { it.name })
    }
}
