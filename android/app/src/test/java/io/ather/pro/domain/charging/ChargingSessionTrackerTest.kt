package io.ather.pro.domain.charging

import org.junit.Assert.*
import org.junit.Test

class ChargingSessionTrackerTest {

    private var now = 1_000L
    private val tracker get() = ChargingSessionTracker { now }

    @Test fun completedSessionCarriesSocEnergyAndCost() {
        val t = tracker
        t.observe(charging = true, soc = 40.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        now += 60 * 60_000L
        val done = t.observe(charging = false, soc = 90.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        requireNotNull(done)
        assertEquals(1_000L, done.startedAtMs)
        assertEquals(60 * 60_000L + 1_000L, done.endedAtMs)
        assertEquals(40.0, done.startSoc!!, 0.001)
        assertEquals(90.0, done.endSoc!!, 0.001)
        assertEquals(1.62, done.energyKWh!!, 0.001)
        assertEquals(12.96, done.costInr!!, 0.001)
        assertNull(t.current())
    }

    @Test fun briefFlagFlickerIsNotASession() {
        val t = tracker
        t.observe(charging = true, soc = 50.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        now += 2 * 60_000L
        assertNull(t.observe(charging = false, soc = 51.0, capacityWh = 3240.0, tariffPerKWh = 8.0))
    }

    @Test fun flatSessionWithoutSocMovementIsDiscarded() {
        val t = tracker
        t.observe(charging = true, soc = 60.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        now += 30 * 60_000L
        assertNull(t.observe(charging = false, soc = 60.0, capacityWh = 3240.0, tariffPerKWh = 8.0))
    }

    @Test fun socMissingAtStartStillTracksFromFirstReading() {
        val t = tracker
        t.observe(charging = true, soc = null, capacityWh = 3240.0, tariffPerKWh = 8.0)
        now += 60_000L
        t.observe(charging = true, soc = 30.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        now += 59 * 60_000L
        val done = t.observe(charging = false, soc = 80.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        requireNotNull(done)
        assertEquals(30.0, done.startSoc!!, 0.001)
        assertEquals(80.0, done.endSoc!!, 0.001)
        assertEquals(1.62, done.energyKWh!!, 0.001)
    }

    @Test fun longSessionWithoutAnySocReadingIsDiscarded() {
        val t = tracker
        t.observe(charging = true, soc = null, capacityWh = 3240.0, tariffPerKWh = 8.0)
        now += 60 * 60_000L
        // No measured movement: neither energy nor end SoC can be derived.
        assertNull(t.observe(charging = false, soc = null, capacityWh = 3240.0, tariffPerKWh = 8.0))
    }

    @Test fun outOfRangeSocIsIgnored() {
        val t = tracker
        t.observe(charging = true, soc = 140.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        assertNull(t.current()?.startSoc)
    }

    @Test fun chargingOnlySocIsKeptNotRegression() {
        val t = tracker
        t.observe(charging = true, soc = 70.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        now += 60_000L
        t.observe(charging = true, soc = 65.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        assertEquals(70.0, t.current()!!.lastSoc!!, 0.001)
    }

    @Test fun staleRestoredSnapshotIsDropped() {
        val t = tracker
        now = 10 * 60 * 60_000L
        t.restore(ChargingSessionTracker.Open(
            startedAtMs = now - 7 * 60 * 60_000L, startSoc = 20.0, lastSoc = 40.0, updatedAtMs = now - 7 * 60 * 60_000L))
        assertNull(t.current())
    }

    @Test fun liveRestoredSnapshotSurvivesProcessDeathAndCompletes() {
        val t = tracker
        now = 3 * 60 * 60_000L
        t.restore(ChargingSessionTracker.Open(
            startedAtMs = now - 30 * 60_000L, startSoc = 10.0, lastSoc = 40.0, updatedAtMs = now - 60_000L))
        requireNotNull(t.current())
        now += 30 * 60_000L
        val done = t.observe(charging = false, soc = 90.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        requireNotNull(done)
        assertEquals(now - 60 * 60_000L, done.startedAtMs)
        assertEquals(90.0, done.endSoc!!, 0.001)
        assertEquals(2.59, done.energyKWh!!, 0.001)
    }

    @Test fun flagFallSocLowerThanLastChargingReadingKeepsTheMaximum() {
        val t = tracker
        t.observe(charging = true, soc = 55.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        now += 30 * 60_000L
        t.observe(charging = true, soc = 100.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        now += 60_000L
        // "Completed" arrives while the BMS settles slightly below the last charge reading.
        val done = t.observe(charging = false, soc = 97.0, capacityWh = 3240.0, tariffPerKWh = 8.0)
        requireNotNull(done)
        assertEquals(100.0, done.endSoc!!, 0.001)
        assertEquals(1.46, done.energyKWh!!, 0.001)
    }

    @Test fun idleTelemetryNeverOpensASession() {
        val t = tracker
        now += 60_000L
        assertNull(t.observe(charging = false, soc = 50.0, capacityWh = 3240.0, tariffPerKWh = 8.0))
        assertNull(t.current())
    }
}
