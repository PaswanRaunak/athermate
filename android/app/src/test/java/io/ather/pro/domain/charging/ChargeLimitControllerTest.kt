package io.ather.pro.domain.charging

import io.ather.pro.domain.model.ScooterTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic charge-limit automation tests. No network. No live scooter commands.
 */
class ChargeLimitControllerTest {

    private val t0 = 2_000_000L

    private fun charging(soc: Double) = ScooterTelemetry(
        batterySoc = soc,
        charging = true,
        chargerConnected = true,
        chargingStatus = "Charging"
    )

    private fun paused(soc: Double) = ScooterTelemetry(
        batterySoc = soc,
        charging = false,
        chargerConnected = true,
        chargingStatus = "Paused",
        remoteChargingAction = "stop"
    )

    private fun unplugged(soc: Double) = ScooterTelemetry(
        batterySoc = soc,
        charging = false,
        chargerConnected = false,
        chargingStatus = "Completed"
    )

    private fun enabledAt(percent: Int = 80) = ChargeLimitController.applySettings(
        previous = ChargeLimitController.Snapshot(),
        enabled = true,
        percent = percent
    )

    @Test
    fun belowThreshold_staysMonitoring_noStop() {
        val state = enabledAt(80)
        val decision = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(70.0),
            lastUpdatedMs = t0,
            nowMs = t0 + 1_000L
        )
        assertEquals(ChargeLimitController.Decision.None, decision)
        assertTrue(state.armed)
        assertEquals(ChargeLimitController.Status.MONITORING, state.status)
    }

    @Test
    fun thresholdCrossing_requestsExactlyOneStop() {
        // The threshold is the rider's actual selection, not a hardcoded 80%.
        val state = enabledAt(73)
        val below = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(72.9),
            lastUpdatedMs = t0,
            nowMs = t0 + 250L
        )
        assertEquals(ChargeLimitController.Decision.None, below)

        val first = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(73.0),
            lastUpdatedMs = t0,
            nowMs = t0 + 500L
        )
        assertTrue(first is ChargeLimitController.Decision.RequestStop)
        val pending = (first as ChargeLimitController.Decision.RequestStop).next
        assertEquals(ChargeLimitController.Status.PENDING, pending.status)
        assertFalse(pending.armed)

        // Duplicate telemetry while pending must not request another stop.
        val dup = ChargeLimitController.onTelemetry(
            state = pending,
            telemetry = charging(74.0),
            lastUpdatedMs = t0 + 1_000L,
            nowMs = t0 + 1_500L
        )
        assertEquals(ChargeLimitController.Decision.None, dup)
    }

    @Test
    fun choosingLimitBelowCurrentSoc_requestsStopImmediately() {
        val selected = enabledAt(91)

        val decision = ChargeLimitController.onTelemetry(
            state = selected,
            telemetry = charging(94.0),
            lastUpdatedMs = t0,
            nowMs = t0 + 100L
        )

        assertTrue(decision is ChargeLimitController.Decision.RequestStop)
        val pending = (decision as ChargeLimitController.Decision.RequestStop).next
        assertEquals(91, pending.percent)
        assertTrue(pending.message.orEmpty().contains("91%"))
    }

    @Test
    fun staleFrame_neverFires() {
        val state = enabledAt(80)
        val decision = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(90.0),
            lastUpdatedMs = t0,
            nowMs = t0 + ChargeLimitController.FRESH_MAX_AGE_MS + 1L
        )
        assertEquals(ChargeLimitController.Decision.None, decision)
        assertTrue(state.armed)
    }

    @Test
    fun unpluggedTelemetry_neverFires() {
        val state = enabledAt(80)
        val decision = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = unplugged(95.0),
            lastUpdatedMs = t0,
            nowMs = t0 + 100L
        )
        assertEquals(ChargeLimitController.Decision.None, decision)
    }

    @Test
    fun pendingConfirm_thenConfirmed() {
        var state = enabledAt(80)
        val stop = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(82.0),
            lastUpdatedMs = t0,
            nowMs = t0
        ) as ChargeLimitController.Decision.RequestStop
        state = stop.next

        val confirmed = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = paused(82.0),
            lastUpdatedMs = t0 + 2_000L,
            nowMs = t0 + 2_000L
        ) as ChargeLimitController.Decision.StateOnly
        assertEquals(ChargeLimitController.Status.CONFIRMED, confirmed.next.status)
        assertFalse(confirmed.next.armed)

        // Still plugged+idle: do not re-arm / do not fire again.
        val still = ChargeLimitController.onTelemetry(
            state = confirmed.next,
            telemetry = paused(83.0),
            lastUpdatedMs = t0 + 3_000L,
            nowMs = t0 + 3_000L
        )
        assertEquals(ChargeLimitController.Decision.None, still)
    }

    @Test
    fun transientDisconnectedFrame_confirmsStopWithoutFalseError() {
        val pending = (ChargeLimitController.onTelemetry(
            state = enabledAt(80),
            telemetry = charging(82.0),
            lastUpdatedMs = t0,
            nowMs = t0
        ) as ChargeLimitController.Decision.RequestStop).next

        val transition = ChargeLimitController.onTelemetry(
            state = pending,
            telemetry = unplugged(82.0),
            lastUpdatedMs = t0 + 1_000L,
            nowMs = t0 + 1_000L
        ) as ChargeLimitController.Decision.StateOnly

        assertEquals(ChargeLimitController.Status.CONFIRMED, transition.next.status)
        assertFalse(transition.next.message.orEmpty().contains("Unplugged", ignoreCase = true))
    }

    @Test
    fun chargingRestartAboveEnabledLimit_requestsAnotherSingleStop() {
        val first = ChargeLimitController.onTelemetry(
            state = enabledAt(80),
            telemetry = charging(82.0),
            lastUpdatedMs = t0,
            nowMs = t0
        ) as ChargeLimitController.Decision.RequestStop
        val confirmed = (ChargeLimitController.onTelemetry(
            state = first.next,
            telemetry = paused(82.0),
            lastUpdatedMs = t0 + 1_000L,
            nowMs = t0 + 1_000L
        ) as ChargeLimitController.Decision.StateOnly).next

        val restarted = ChargeLimitController.onTelemetry(
            state = confirmed,
            telemetry = charging(83.0),
            lastUpdatedMs = t0 + 2_000L,
            nowMs = t0 + 2_000L
        )
        assertTrue(restarted is ChargeLimitController.Decision.RequestStop)
        val pendingAgain = (restarted as ChargeLimitController.Decision.RequestStop).next

        val duplicate = ChargeLimitController.onTelemetry(
            state = pendingAgain,
            telemetry = charging(83.0),
            lastUpdatedMs = t0 + 2_500L,
            nowMs = t0 + 2_500L
        )
        assertEquals(ChargeLimitController.Decision.None, duplicate)
    }

    @Test
    fun timeout_allowsRetry() {
        var state = enabledAt(80)
        val stop = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(85.0),
            lastUpdatedMs = t0,
            nowMs = t0
        ) as ChargeLimitController.Decision.RequestStop
        state = stop.next

        val timedOut = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(86.0),
            lastUpdatedMs = t0 + ChargeLimitController.CONFIRM_TIMEOUT_MS,
            nowMs = t0 + ChargeLimitController.CONFIRM_TIMEOUT_MS
        ) as ChargeLimitController.Decision.StateOnly
        assertEquals(ChargeLimitController.Status.ERROR, timedOut.next.status)

        val retried = ChargeLimitController.retry(timedOut.next)
        assertEquals(ChargeLimitController.Status.MONITORING, retried.status)
        assertTrue(retried.armed)

        val again = ChargeLimitController.onTelemetry(
            state = retried,
            telemetry = charging(87.0),
            lastUpdatedMs = t0 + ChargeLimitController.CONFIRM_TIMEOUT_MS + 1_000L,
            nowMs = t0 + ChargeLimitController.CONFIRM_TIMEOUT_MS + 1_000L
        )
        assertTrue(again is ChargeLimitController.Decision.RequestStop)
    }

    @Test
    fun sessionRearm_afterUnplug() {
        var state = enabledAt(80)
        val stop = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(80.0),
            lastUpdatedMs = t0,
            nowMs = t0
        ) as ChargeLimitController.Decision.RequestStop
        state = stop.next
        val confirmed = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = paused(80.0),
            lastUpdatedMs = t0 + 1_000L,
            nowMs = t0 + 1_000L
        ) as ChargeLimitController.Decision.StateOnly
        state = confirmed.next

        val afterUnplug = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = unplugged(80.0),
            lastUpdatedMs = t0 + 5_000L,
            nowMs = t0 + 5_000L
        ) as ChargeLimitController.Decision.StateOnly
        assertEquals(ChargeLimitController.Status.MONITORING, afterUnplug.next.status)
        assertTrue(afterUnplug.next.armed)

        val nextSession = ChargeLimitController.onTelemetry(
            state = afterUnplug.next,
            telemetry = charging(81.0),
            lastUpdatedMs = t0 + 10_000L,
            nowMs = t0 + 10_000L
        )
        assertTrue(nextSession is ChargeLimitController.Decision.RequestStop)
    }

    @Test
    fun settingsChange_rearmsWithoutUnplug() {
        var state = enabledAt(90)
        // Cross 90 once and confirm.
        val stop = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(91.0),
            lastUpdatedMs = t0,
            nowMs = t0
        ) as ChargeLimitController.Decision.RequestStop
        state = (ChargeLimitController.onTelemetry(
            state = stop.next,
            telemetry = paused(91.0),
            lastUpdatedMs = t0 + 1_000L,
            nowMs = t0 + 1_000L
        ) as ChargeLimitController.Decision.StateOnly).next
        assertEquals(ChargeLimitController.Status.CONFIRMED, state.status)
        assertFalse(state.armed)

        // Deliberate percent change re-arms.
        state = ChargeLimitController.applySettings(state, enabled = true, percent = 70)
        assertTrue(state.armed)
        assertEquals(ChargeLimitController.Status.MONITORING, state.status)

        // Still plugged and charging above new limit → one stop.
        val again = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(91.0),
            lastUpdatedMs = t0 + 2_000L,
            nowMs = t0 + 2_000L
        )
        assertTrue(again is ChargeLimitController.Decision.RequestStop)
    }

    @Test
    fun disabled_neverRequestsStop() {
        val state = ChargeLimitController.Snapshot(
            enabled = false,
            percent = 50,
            status = ChargeLimitController.Status.DISABLED,
            armed = false
        )
        val decision = ChargeLimitController.onTelemetry(
            state = state,
            telemetry = charging(99.0),
            lastUpdatedMs = t0,
            nowMs = t0
        )
        assertEquals(ChargeLimitController.Decision.None, decision)
    }
}
