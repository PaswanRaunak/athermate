package io.ather.pro.data.analytics

import io.ather.pro.domain.analytics.ScorecardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleHealthReportParserTest {

    private val client = VehicleHealthReportClient()

    @Test
    fun parsesSanitizedVehicleHealthFixture() {
        val raw = readFixture("analytics_fixtures/vehicle_health_report.json")
        val report = VehicleHealthReportParser.parse(raw)

        assertNotNull(report)
        assertEquals("Ather 450X", report!!.vehicle?.name)
        assertEquals("KA01****34", report.vehicle?.registrationMasked)
        assertEquals(12500, report.vehicle?.odoKms)

        assertEquals(86.0f, report.overall!!.score!!, 0.01f)
        assertEquals("Good", report.overall!!.label)
        assertEquals(100, report.overall!!.maxScore)

        assertEquals(2, report.components.size)
        assertEquals("Battery", report.components[0].name)
        assertEquals(90.0f, report.components[0].score!!, 0.01f)
        assertEquals("Healthy", report.components[0].tag?.text)

        assertEquals(1, report.wearAndTear.size)
        assertEquals("Front tyre", report.wearAndTear[0].name)
        assertEquals(64, report.wearAndTear[0].lifePercent)

        assertEquals("INR", report.resale?.currency)
        assertEquals(90000, report.resale?.minValue)
        assertEquals("₹90,000 – ₹1,10,000", report.resale?.displayText)
        assertEquals("2026-09-20T10:00:00Z", report.meta?.lastUpdated)
        assertTrue(report.sourceLabel.contains("not BMS SoH"))
    }

    @Test
    fun emptyOrMalformedReturnsNull() {
        assertNull(VehicleHealthReportParser.parse(""))
        assertNull(VehicleHealthReportParser.parse("{}"))
        assertNull(VehicleHealthReportParser.parse("""{"data":{}}"""))
        assertNull(VehicleHealthReportParser.parse("not-json"))
    }

    @Test
    fun clientMaps404AndEmptyToUnavailable() {
        val notFound = client.parseBodyOrUnavailable(404, null)
        assertTrue(notFound is ScorecardState.Unavailable)
        assertTrue(
            (notFound as ScorecardState.Unavailable).reason.contains("404")
        )
        assertTrue(client.parseBodyOrUnavailable(404, "{}") is ScorecardState.Unavailable)
        assertTrue(client.parseBodyOrUnavailable(200, "") is ScorecardState.Unavailable)
        assertTrue(client.parseBodyOrUnavailable(200, """{"data":{}}""") is ScorecardState.Unavailable)
    }

    @Test
    fun clientMapsHttpErrorsAndSuccess() {
        val err = client.parseBodyOrUnavailable(500, "boom")
        assertTrue(err is ScorecardState.Error)
        assertEquals("HTTP 500", (err as ScorecardState.Error).message)

        val raw = readFixture("analytics_fixtures/vehicle_health_report.json")
        val ok = client.parseBodyOrUnavailable(200, raw)
        assertTrue(ok is ScorecardState.Available)
        assertEquals(86.0f, (ok as ScorecardState.Available).report.overall!!.score!!, 0.01f)
    }

    private fun readFixture(path: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing fixture $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }
}
