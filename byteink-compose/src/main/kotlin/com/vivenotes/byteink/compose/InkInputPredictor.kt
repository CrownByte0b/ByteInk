package com.vivenotes.byteink.compose

import kotlin.math.hypot

/** One predictor per pointer. Forecasts use the same coordinates and clock as real observations. */
public interface InkInputPredictor {
    public fun record(sample: InkPointerSample)
    public fun predict(targetUptimeMillis: Long): List<InkPointerSample>
    public fun reset()
}

/**
 * Conservative linear desktop forecast with a bounded horizon and distance. A reversal, stationary
 * observation, long gap, or tool change resets velocity. Attributes hold the last measured values;
 * pressure and tilt trends are not invented. Hosts can substitute a device-specific predictor.
 */
public class InkLinearPredictor(
    public val maxPredictionMillis: Long = 24L,
    public val maxPredictionDistance: Float = 32f,
) : InkInputPredictor {
    init {
        require(maxPredictionMillis in 1L..100L)
        require(maxPredictionDistance.isFinite() && maxPredictionDistance > 0f)
    }
    private var last: InkPointerSample? = null
    private var vx: Double = 0.0
    private var vy: Double = 0.0
    private var moving: Boolean = false

    override public fun record(sample: InkPointerSample) {
        val previous = last
        if (previous != null && sample.uptimeMillis < previous.uptimeMillis) return
        if (previous != null && sample.uptimeMillis == previous.uptimeMillis) {
            // Retain the newest position, without estimating velocity from a zero-time interval.
            last = sample
            moving = false
            return
        }
        val dt = if (previous == null) 0L else sample.uptimeMillis - previous.uptimeMillis
        if (previous == null || previous.toolType != sample.toolType || dt !in 1L..100L) {
            moving = false
        } else {
            val nx = (sample.x.toDouble() - previous.x) / dt
            val ny = (sample.y.toDouble() - previous.y) / dt
            if (nx == 0.0 && ny == 0.0 || moving && vx * nx + vy * ny < 0.0) {
                moving = false
            } else {
                vx = if (moving) 0.75 * nx + 0.25 * vx else nx
                vy = if (moving) 0.75 * ny + 0.25 * vy else ny
                moving = true
            }
        }
        last = sample
    }

    override public fun predict(targetUptimeMillis: Long): List<InkPointerSample> {
        val sample = last ?: return emptyList()
        val dt = targetUptimeMillis - sample.uptimeMillis
        if (!moving || dt !in 1L..maxPredictionMillis) return emptyList()
        val length = hypot(vx, vy) * dt
        val scale = if (length > maxPredictionDistance) maxPredictionDistance / length else 1.0
        val x = (sample.x + vx * dt * scale).toFloat()
        val y = (sample.y + vy * dt * scale).toFloat()
        if (!x.isFinite() || !y.isFinite() || x == sample.x && y == sample.y) return emptyList()
        return listOf(sample.copy(x = x, y = y, uptimeMillis = targetUptimeMillis))
    }

    override public fun reset() {
        last = null
        vx = 0.0; vy = 0.0; moving = false
    }
}
