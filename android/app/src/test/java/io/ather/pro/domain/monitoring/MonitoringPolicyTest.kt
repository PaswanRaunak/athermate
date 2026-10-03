package io.ather.pro.domain.monitoring

import org.junit.Assert.*
import org.junit.Test

class MonitoringPolicyTest {
    @Test fun signedOutNeverMonitors() {
        for (enabled in listOf(false, true)) for (limit in listOf(false, true)) {
            assertFalse(MonitoringPolicy.shouldRun(false, enabled, limit, charging = true))
        }
    }
    @Test fun enabledLimitKeepsMonitoringBeforeChargingStarts() {
        assertTrue(MonitoringPolicy.shouldRun(true, false, true, charging = true))
        assertTrue(MonitoringPolicy.shouldRun(true, true, false, charging = true))
        assertFalse(MonitoringPolicy.shouldRun(true, false, false, charging = true))
        assertTrue(MonitoringPolicy.shouldRun(true, true, true, charging = false))
        assertTrue(MonitoringPolicy.shouldRun(true, false, true, charging = false))
        assertFalse(MonitoringPolicy.shouldRun(true, true, false, charging = false))
        assertTrue(MonitoringPolicy.shouldRun(true, false, true, charging = false, awaitingStop = true))
    }
}
