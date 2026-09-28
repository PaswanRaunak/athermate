package io.ather.pro.data.api

import org.junit.Assert.*
import org.junit.Test

class ReportedSohTest {
    private val api = AtherApiClient()
    @Test fun explicitSohPercentageIsParsedWithoutUsingAHealthScore() {
        val data = api.parseTelemetry("""{"telemetry.bike":{"battery_soc":60,"battery_soh_percent":93.4},"health_score":100}""")!!
        assertEquals(93.4, data.reportedSohPercent!!, 0.001)
        assertNull(api.parseTelemetry("""{"telemetry.bike":{"battery_soc":60,"health_score":95}}""")!!.reportedSohPercent)
    }
    @Test fun invalidSohIsNotDisplayed() {
        assertNull(api.parseTelemetry("""{"telemetry.bike":{"battery_soc":60,"battery_soh_percent":140}}""")!!.reportedSohPercent)
    }
}
