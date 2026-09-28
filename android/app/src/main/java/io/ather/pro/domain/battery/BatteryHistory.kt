package io.ather.pro.domain.battery

import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TelemetrySample
import kotlin.math.abs

/** Samples actual battery reports. Unrelated socket packets do not repeat stale SoC. */
object BatteryHistory {
    const val SAMPLE_INTERVAL_MS = 1_000L
    const val MAX_SAMPLES = 3_600
    const val MAX_LINE_GAP_MS = 120_000L

    fun record(
        history: List<TelemetrySample>,
        report: ScooterTelemetry,
        observedAt: Long
    ): List<TelemetrySample> {
        val soc = report.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?: return history
        if (observedAt <= 0L) return history
        val last = history.lastOrNull()
        // Keep the last sample's time fixed. Replacing it on every packet previously
        // prevented the interval from ever elapsing on a busy telemetry stream.
        if (last != null && observedAt - last.timestamp < SAMPLE_INTERVAL_MS) return history
        return (history + TelemetrySample(
            timestamp = observedAt,
            batterySoc = soc,
            // Legacy storage fields; speed is not plotted because missing GPS speed
            // cannot be distinguished from standstill in previously stored samples.
            speedKmh = report.gps?.speed?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            mode = report.mode ?: last?.mode ?: "Unknown"
        )).takeLast(MAX_SAMPLES)
    }

    fun visible(
        history: List<TelemetrySample>,
        nowMs: Long,
        durationMs: Long?
    ): List<TelemetrySample> {
        val start = durationMs?.let { nowMs - it } ?: 0L
        return history.filter {
            it.timestamp > 0L && it.timestamp in start..nowMs &&
                it.batterySoc.isFinite() && it.batterySoc in 0.0..100.0
        }.distinctBy { it.timestamp }.sortedBy { it.timestamp }
    }

    /** Select a measured point by time, not by sample index or invented interpolation. */
    fun nearest(
        points: List<TelemetrySample>,
        startMs: Long,
        endMs: Long,
        fraction: Float
    ): TelemetrySample? {
        if (points.isEmpty() || !fraction.isFinite()) return null
        val span = (endMs - startMs).coerceAtLeast(1L)
        val target = startMs + (fraction.coerceIn(0f, 1f) * span.toDouble()).toLong()
        return points.minByOrNull { abs(it.timestamp - target) }
    }
}
