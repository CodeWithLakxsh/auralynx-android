package app.auralynx.experimental.research

import app.auralynx.core.protocol.aap.AapEvent

/**
 * Turns raw head-tracking samples into a *relative* yaw estimate.
 *
 * The scale and axis assignment of the raw orientation fields are undocumented. Auralynx exposes
 * the raw integers and an uncalibrated relative angle obtained by: (1) the user sets a "looking
 * forward" reference, (2) the user turns 90 degrees and confirms, giving a scale. Until both steps
 * are done, [yawDegrees] returns null. Nothing here claims physical accuracy.
 */
class HeadTrackingInterpreter {
    private var reference: Int? = null
    private var unitsPer90: Double? = null
    var axis: Axis = Axis.ORIENTATION_1

    enum class Axis { ORIENTATION_1, ORIENTATION_2, ORIENTATION_3 }

    fun raw(sample: AapEvent.HeadTracking): Int = when (axis) {
        Axis.ORIENTATION_1 -> sample.orientation1
        Axis.ORIENTATION_2 -> sample.orientation2
        Axis.ORIENTATION_3 -> sample.orientation3
    }

    fun setForward(sample: AapEvent.HeadTracking) { reference = raw(sample) }

    fun setNinetyDegrees(sample: AapEvent.HeadTracking) {
        val ref = reference ?: return
        val delta = (raw(sample) - ref).toDouble()
        if (kotlin.math.abs(delta) > 1.0) unitsPer90 = delta
    }

    val calibrated: Boolean get() = reference != null && unitsPer90 != null

    fun yawDegrees(sample: AapEvent.HeadTracking): Double? {
        val ref = reference ?: return null
        val scale = unitsPer90 ?: return null
        return ((raw(sample) - ref) / scale * 90.0).coerceIn(-180.0, 180.0)
    }

    fun reset() { reference = null; unitsPer90 = null }
}
