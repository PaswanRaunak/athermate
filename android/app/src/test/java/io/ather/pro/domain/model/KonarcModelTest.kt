package io.ather.pro.domain.model

import io.ather.pro.domain.range.RangeEstimator
import io.ather.pro.domain.range.RideMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class KonarcModelTest {
    @Test fun konarcWithoutPackSizeResolvesToTheLargestPack() {
        val profile = VehicleProfile(modelType = "Konarc")
        assertEquals(ScooterModel.ATHER_KONARC_3_5, profile.resolvedModel)
        assertEquals("Konarc", profile.displayName)
    }

    @Test fun modelCodeTwoPointOneResolvesToKonarc21() {
        val profile = VehicleProfile(modelType = "Konarc", modelCode = "2.1")
        assertEquals(ScooterModel.ATHER_KONARC_2_1, profile.resolvedModel)
    }

    @Test fun modelCodeTwoPointSevenResolvesToKonarc27() {
        val profile = VehicleProfile(modelType = "Konarc", modelCode = "2.7")
        assertEquals(ScooterModel.ATHER_KONARC_2_7, profile.resolvedModel)
    }

    @Test fun plain450XStillResolvesToNull() {
        assertNull(VehicleProfile(modelType = "450X").resolvedModel)
    }

    @Test fun powerIsOnlyShownOnKonarc() {
        val telemetry = ScooterTelemetry(
            mode = "power",
            modeRanges = RideMode.entries.associate { it.apiName to ModeRange(rawRangeKm = 40.0) }
        )
        assertEquals(
            listOf("SmartEco", "Eco", "Power"),
            RangeEstimator.modes(telemetry, ScooterModel.ATHER_KONARC_3_5).map { it.name }
        )
        assertEquals(
            "Power",
            RangeEstimator.modes(telemetry, ScooterModel.ATHER_KONARC_2_7).single { it.active }.name
        )
        for (model in listOf(
            ScooterModel.ATHER_450X_3_7,
            ScooterModel.ATHER_450X_2_9,
            ScooterModel.ATHER_450S,
            ScooterModel.ATHER_APEX,
            ScooterModel.ATHER_RIZTA_3_7,
            ScooterModel.ATHER_RIZTA_2_9,
            null
        )) {
            assertFalse(RangeEstimator.modes(telemetry, model).any { it.name == "Power" })
        }
    }

    @Test fun fourHundredFiftySNamesStaySmartEcoEcoRideSport() {
        val telemetry = ScooterTelemetry(
            mode = "ride",
            modeRanges = RideMode.entries.associate { it.apiName to ModeRange(rawRangeKm = 30.0) }
        )
        assertEquals(
            listOf("SmartEco", "Eco", "Ride", "Sport"),
            RangeEstimator.modes(telemetry, ScooterModel.ATHER_450S).map { it.name }
        )
    }
}
