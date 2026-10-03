package io.ather.pro.domain.computation

/** Primitive computation boundary; Android installs its native implementation at startup. */
interface TelemetryMath {
    fun historyIndices(timestamps: LongArray, values: DoubleArray, start: Long, end: Long): IntArray
    fun scaleRange(current: Double, mode: Double, active: Double): Double
    fun chargeEstimate(soc: Double, target: Double, capacity: Double, tariff: Double,
        range: Double, eta80: Double, eta100: Double): DoubleArray
    fun chargeTimeEstimate(soc: Double, target: Double, capacity: Double, power: Double,
        eta80: Double, eta100: Double, observedRate: Double): DoubleArray
}

object TelemetryComputation {
    @Volatile var engine: TelemetryMath? = null
    fun requireEngine(): TelemetryMath = checkNotNull(engine) { "Telemetry computation is not initialized" }
}
