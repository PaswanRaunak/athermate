package io.ather.pro

import io.ather.pro.domain.computation.TelemetryComputation
import io.ather.pro.domain.computation.TelemetryMath

/**
 * JVM twin of `rust/ather-math` for unit tests. Android installs the native engine in
 * `AtherApplication`; the JVM test classpath has no `.so`/`.dll`, so tests install this
 * reference implementation instead of skipping the real domain logic.
 */
object TestTelemetry {
    fun install() {
        TelemetryComputation.engine = ReferenceTelemetryMath
    }
}

/** Mirrors the primitive semantics of rust/ather-math/src/lib.rs. */
object ReferenceTelemetryMath : TelemetryMath {
    override fun historyIndices(timestamps: LongArray, values: DoubleArray, start: Long, end: Long): IntArray {
        val kept = timestamps.indices.filter { index ->
            val value = values.getOrNull(index) ?: return@filter false
            val time = timestamps[index]
            time > 0L && time >= start && time <= end && value.isFinite() && value in 0.0..100.0
        }.sortedBy { timestamps[it] }
        val seen = HashSet<Long>()
        return kept.filter { seen.add(timestamps[it]) }.toIntArray()
    }

    override fun scaleRange(current: Double, mode: Double, active: Double): Double {
        val scaled = current * mode / active
        return if (scaled.isFinite() && scaled >= 0.0) scaled else Double.NaN
    }

    override fun chargeEstimate(soc: Double, target: Double, capacity: Double, tariff: Double,
        range: Double, eta80: Double, eta100: Double): DoubleArray {
        val clamped = target.coerceIn(0.0, 100.0)
        val remaining = (clamped - soc).coerceAtLeast(0.0)
        val energy = capacity * remaining / 100_000.0
        val eta = when {
            remaining == 0.0 -> 0.0
            clamped <= 80.0 && soc < 80.0 && eta80.isFinite() && eta80 >= 0.0 -> eta80 * remaining / (80.0 - soc)
            soc < 100.0 && eta100.isFinite() && eta100 >= 0.0 -> eta100 * remaining / (100.0 - soc)
            else -> Double.NaN
        }
        val projected = if (soc >= 5.0 && range.isFinite() && range >= 0.0) range * clamped / soc else Double.NaN
        return doubleArrayOf(remaining, energy, energy * tariff, projected, eta)
    }

    override fun chargeTimeEstimate(soc: Double, target: Double, capacity: Double, power: Double,
        eta80: Double, eta100: Double, observedRate: Double): DoubleArray {
        val remaining = (target.coerceIn(0.0, 100.0) - soc).coerceAtLeast(0.0)
        val (minutes, basis) = when {
            remaining == 0.0 -> 0.0 to 1.0
            target <= 80.0 && soc < 80.0 && eta80.isFinite() && eta80 > 0.0 ->
                eta80 * remaining / (80.0 - soc) to 1.0
            soc < 100.0 && eta100.isFinite() && eta100 > 0.0 ->
                eta100 * remaining / (100.0 - soc) to 1.0
            observedRate.isFinite() && observedRate in 0.01..10.0 -> remaining / observedRate to 2.0
            else -> capacity * remaining / 100.0 / power * 60.0 to 3.0
        }
        return doubleArrayOf(minutes, basis)
    }
}