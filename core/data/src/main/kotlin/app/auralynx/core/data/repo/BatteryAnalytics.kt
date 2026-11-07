package app.auralynx.core.data.repo

import app.auralynx.core.protocol.model.ChargingState

/** A measured point used by the estimator. */
data class BatteryPoint(val timeMillis: Long, val level: Int, val charging: ChargingState)

/**
 * Discharge-rate and remaining-runtime **estimates**. Inputs are only real measurements; outputs are
 * always presented in the UI as estimates with their basis (sample count and time span).
 */
data class DischargeEstimate(
    /** Percentage points per hour (positive = draining). */
    val ratePercentPerHour: Double,
    val remainingMinutes: Int?,
    val samples: Int,
    val spanMinutes: Int,
)

object BatteryAnalytics {
    /** A gap longer than this splits discharge segments (e.g. buds went back into the case). */
    private const val MAX_GAP_MILLIS = 20 * 60_000L
    private const val MIN_SAMPLES = 3
    private const val MIN_SPAN_MILLIS = 10 * 60_000L

    /**
     * Least-squares slope over the most recent uninterrupted discharging segment. Returns null when
     * there is not enough evidence; the UI then shows "Not enough data yet" instead of a number.
     */
    fun estimate(points: List<BatteryPoint>): DischargeEstimate? {
        val sorted = points.sortedBy { it.timeMillis }
        val segment = ArrayDeque<BatteryPoint>()
        for (p in sorted.asReversed()) {
            if (p.charging == ChargingState.CHARGING) break
            val previous = segment.firstOrNull()
            if (previous != null && previous.timeMillis - p.timeMillis > MAX_GAP_MILLIS) break
            // A level increase without a charging flag means a different segment (case top-up).
            if (previous != null && p.level < previous.level) break
            segment.addFirst(p)
        }
        if (segment.size < MIN_SAMPLES) return null
        val span = segment.last().timeMillis - segment.first().timeMillis
        if (span < MIN_SPAN_MILLIS) return null

        val t0 = segment.first().timeMillis
        val xs = segment.map { (it.timeMillis - t0) / 3_600_000.0 }
        val ys = segment.map { it.level.toDouble() }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val rate = -slope
        val remaining = if (rate > 0.5) ((segment.last().level / rate) * 60).toInt() else null
        return DischargeEstimate(rate, remaining, segment.size, (span / 60_000).toInt())
    }

    /** Charging sessions observed in the data: start/end time and level gained. */
    data class ChargeSession(val startMillis: Long, val endMillis: Long, val fromLevel: Int, val toLevel: Int)

    fun chargeSessions(points: List<BatteryPoint>): List<ChargeSession> {
        val out = mutableListOf<ChargeSession>()
        var start: BatteryPoint? = null
        var last: BatteryPoint? = null
        for (p in points.sortedBy { it.timeMillis }) {
            if (p.charging == ChargingState.CHARGING) {
                if (start == null) start = p
                last = p
            } else if (start != null && last != null) {
                if (last.level > start.level) out += ChargeSession(start.timeMillis, last.timeMillis, start.level, last.level)
                start = null; last = null
            }
        }
        if (start != null && last != null && last.level > start.level) {
            out += ChargeSession(start.timeMillis, last.timeMillis, start.level, last.level)
        }
        return out
    }
}
