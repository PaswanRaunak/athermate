package io.ather.pro.util

import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TpmsData

/** Pure threshold + latch logic for charging and TPMS alerts (unit-testable). */
object VehicleAlertEvaluator {

    const val CHARGE_80 = 80.0
    const val CHARGE_100 = 100.0

    /** Absolute low-pressure floor (psi). Only evaluated when a reading is present. */
    const val TPMS_LOW_PSI = 28.0

    /** Must recover above low + hysteresis before another low alert can fire. */
    const val TPMS_RECOVER_PSI = 30.0

    data class ChargingLatch(
        val sessionActive: Boolean = false,
        val notified80: Boolean = false,
        val notified100: Boolean = false
    )

    data class ChargingDecision(
        val latch: ChargingLatch,
        val showProgress: Boolean,
        val fire80: Boolean,
        val fire100: Boolean,
        val cancelProgress: Boolean
    )

    data class TpmsLatch(
        val frontLowLatched: Boolean = false,
        val rearLowLatched: Boolean = false
    )

    data class TpmsDecision(
        val latch: TpmsLatch,
        val fireFrontLow: Boolean,
        val fireRearLow: Boolean,
        val frontPsi: Double? = null,
        val rearPsi: Double? = null
    )

    fun isChargingConnected(telemetry: ScooterTelemetry): Pair<Boolean, Boolean> {
        val isCharging = telemetry.charging == true ||
            telemetry.chargingStatus?.equals("Charging", ignoreCase = true) == true
        val isConnected = telemetry.chargerConnected == true || isCharging
        return isConnected to isCharging
    }

    fun evaluateCharging(
        previous: ChargingLatch,
        isConnected: Boolean,
        isCharging: Boolean,
        soc: Double?
    ): ChargingDecision {
        if (!isConnected) {
            val cancel = previous.sessionActive
            return ChargingDecision(
                latch = ChargingLatch(),
                showProgress = false,
                fire80 = false,
                fire100 = false,
                cancelProgress = cancel
            )
        }
        if (soc == null || !soc.isFinite()) {
            return ChargingDecision(
                latch = previous,
                showProgress = false,
                fire80 = false,
                fire100 = false,
                cancelProgress = false
            )
        }

        var notified80 = previous.notified80
        var notified100 = previous.notified100
        var sessionActive = previous.sessionActive

        if (!sessionActive) {
            sessionActive = true
            // Suppress milestones already reached at plug-in.
            notified80 = soc >= CHARGE_80
            notified100 = soc >= CHARGE_100
        }

        val fire80 = soc >= CHARGE_80 && !notified80
        val fire100 = soc >= CHARGE_100 && !notified100
        if (fire80) notified80 = true
        if (fire100) notified100 = true

        return ChargingDecision(
            latch = ChargingLatch(sessionActive, notified80, notified100),
            showProgress = isCharging,
            fire80 = fire80,
            fire100 = fire100,
            cancelProgress = !isCharging && previous.sessionActive
        )
    }

    fun evaluateTpms(previous: TpmsLatch, tpms: TpmsData?): TpmsDecision {
        if (tpms == null || !tpms.hasPressure) {
            // Unavailable accessory: never invent alerts; clear latches so a later
            // genuine reading can alert once.
            return TpmsDecision(latch = TpmsLatch(), fireFrontLow = false, fireRearLow = false)
        }

        val front = tpms.frontPressurePsi?.takeIf { it.isFinite() && it > 0.0 }
        val rear = tpms.rearPressurePsi?.takeIf { it.isFinite() && it > 0.0 }

        var frontLatched = previous.frontLowLatched
        var rearLatched = previous.rearLowLatched

        if (front != null && front >= TPMS_RECOVER_PSI) frontLatched = false
        if (rear != null && rear >= TPMS_RECOVER_PSI) rearLatched = false

        val fireFront = front != null && front < TPMS_LOW_PSI && !frontLatched
        val fireRear = rear != null && rear < TPMS_LOW_PSI && !rearLatched
        if (fireFront) frontLatched = true
        if (fireRear) rearLatched = true

        return TpmsDecision(
            latch = TpmsLatch(frontLatched, rearLatched),
            fireFrontLow = fireFront,
            fireRearLow = fireRear,
            frontPsi = front,
            rearPsi = rear
        )
    }
}
