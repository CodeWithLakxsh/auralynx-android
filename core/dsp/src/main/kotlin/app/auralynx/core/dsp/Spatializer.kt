package app.auralynx.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

/**
 * Binaural rendering of a point source using the Brown & Duda (1998) spherical-head model:
 * a one-pole/one-zero head-shadow filter per ear plus a Woodworth-style interaural time delay.
 *
 * This is a generic, non-personalised model. It does not reproduce any vendor's personalised
 * spatial audio and makes no claim of measured HRTF accuracy.
 */
class BinauralSource(private val sampleRate: Double, azimuthDegrees: Double = 0.0) {

    private val left = Ear(-90.0)
    private val right = Ear(90.0)

    var azimuthDegrees: Double = azimuthDegrees
        set(value) {
            field = ((value % 360) + 540) % 360 - 180 // normalise to [-180, 180)
            left.update(field); right.update(field)
        }

    init { this.azimuthDegrees = azimuthDegrees }

    fun process(x: Float, out: FloatArray) {
        out[0] = left.process(x)
        out[1] = right.process(x)
    }

    fun reset() { left.reset(); right.reset() }

    private inner class Ear(private val earAngle: Double) {
        private val buffer = FloatArray(MAX_DELAY_FRAMES + 2)
        private var write = 0
        private var delayFrames = 0.0
        // head-shadow filter state, bilinear transform of H(s) = (alpha s + beta) / (s + beta)
        private var b0 = 1.0; private var b1 = 0.0; private var a1 = 0.0
        private var x1 = 0.0; private var y1 = 0.0

        fun update(sourceAzimuth: Double) {
            // Angle between the source and this ear's axis, in radians, folded into [0, pi].
            val theta = Math.toRadians(abs(((sourceAzimuth - earAngle) % 360 + 540) % 360 - 180))
            val alpha = 1.05 + 0.95 * cos(theta / (150.0 / 180.0 * PI) * PI)
            val beta = 2 * SPEED_OF_SOUND / HEAD_RADIUS
            val k = 2 * sampleRate
            val norm = k + beta
            b0 = (alpha * k + beta) / norm
            b1 = (beta - alpha * k) / norm
            a1 = (beta - k) / norm
            // Woodworth delay relative to the earliest possible arrival, always >= 0.
            val delaySeconds = if (theta < PI / 2) {
                HEAD_RADIUS / SPEED_OF_SOUND * (1 - cos(theta))
            } else {
                HEAD_RADIUS / SPEED_OF_SOUND * (1 + theta - PI / 2)
            }
            delayFrames = (delaySeconds * sampleRate).coerceAtMost(MAX_DELAY_FRAMES.toDouble())
        }

        fun process(x: Float): Float {
            buffer[write] = x
            val readPos = write - delayFrames
            val i0 = Math.floorMod(Math.floor(readPos).toInt(), buffer.size)
            val i1 = (i0 + 1) % buffer.size
            val frac = readPos - Math.floor(readPos)
            // linear interpolation between the two samples around the fractional read position
            val delayed = buffer[i0] * (1 - frac) + buffer[i1] * frac
            write = (write + 1) % buffer.size
            val y = b0 * delayed + b1 * x1 - a1 * y1
            x1 = delayed; y1 = y
            return y.toFloat()
        }

        fun reset() { buffer.fill(0f); x1 = 0.0; y1 = 0.0 }
    }

    companion object {
        const val HEAD_RADIUS = 0.0875 // metres
        const val SPEED_OF_SOUND = 343.0 // m/s
        const val MAX_DELAY_FRAMES = 64
    }
}

enum class SpatialMode(val displayName: String) {
    OFF("Off"),
    CROSSFEED("Headphone crossfeed"),
    VIRTUAL_SPEAKERS("Virtual speakers"),
    VIRTUAL_ROOM("Virtual room"),
}

/**
 * App-local stereo spatialiser: renders the left/right channels as two virtual loudspeakers at
 * +/-[speakerAngle] degrees, optionally adds early room reflections, and can rotate the whole
 * sound stage by a head yaw (used by the experimental head-tracking path).
 */
class StereoSpatializer(private val sampleRate: Double) {

    var mode: SpatialMode = SpatialMode.OFF
    var speakerAngle: Double = 30.0
        set(value) { field = value.coerceIn(10.0, 90.0); updateAngles() }

    /** Head yaw in degrees (positive = head turned right). The stage counter-rotates to stay fixed. */
    var headYaw: Double = 0.0
        set(value) { field = value; updateAngles() }

    private val leftSpeaker = BinauralSource(sampleRate)
    private val rightSpeaker = BinauralSource(sampleRate)
    private val reflections = listOf(
        Reflection(0.011, -8.0, -110.0), Reflection(0.017, -9.5, 110.0),
        Reflection(0.023, -11.0, -150.0), Reflection(0.029, -12.5, 150.0),
    ).map { it.build() }
    /** Normalises speakers + reflections so their summed linear gain matches [OUTPUT_GAIN] x 2 sources. */
    private val roomGain: Float = OUTPUT_GAIN * 2f / (2f + reflections.sumOf { it.gain.toDouble() }.toFloat())
    private val crossLowPass = Biquad.lowPass(700.0, 0.707, sampleRate, channels = 2)
    private val crossDelay = DelayLine(ceil(0.0003 * sampleRate).toInt() + 1, 2)
    private val tmp = FloatArray(2)

    init { updateAngles() }

    /** Worst-case algorithmic latency in frames (reflections are echoes, not latency). */
    val algorithmicLatencyFrames: Int
        get() = when (mode) {
            SpatialMode.OFF -> 0
            SpatialMode.CROSSFEED -> crossDelay.length
            SpatialMode.VIRTUAL_SPEAKERS, SpatialMode.VIRTUAL_ROOM -> BinauralSource.MAX_DELAY_FRAMES
        }

    fun process(interleaved: FloatArray, frames: Int = interleaved.size / 2) {
        when (mode) {
            SpatialMode.OFF -> return
            SpatialMode.CROSSFEED -> for (f in 0 until frames) {
                val l = interleaved[2 * f]; val r = interleaved[2 * f + 1]
                val dl = crossDelay.push(crossLowPass.process(l, 0), 0)
                val dr = crossDelay.push(crossLowPass.process(r, 1), 1)
                interleaved[2 * f] = (l * 0.78f + dr * 0.28f)
                interleaved[2 * f + 1] = (r * 0.78f + dl * 0.28f)
            }
            SpatialMode.VIRTUAL_SPEAKERS, SpatialMode.VIRTUAL_ROOM -> for (f in 0 until frames) {
                val l = interleaved[2 * f]; val r = interleaved[2 * f + 1]
                leftSpeaker.process(l, tmp); var outL = tmp[0]; var outR = tmp[1]
                rightSpeaker.process(r, tmp); outL += tmp[0]; outR += tmp[1]
                if (mode == SpatialMode.VIRTUAL_ROOM) {
                    val mid = (l + r) * 0.5f
                    for (refl in reflections) {
                        refl.source.process(refl.delay.push(mid, 0) * refl.gain, tmp)
                        outL += tmp[0]; outR += tmp[1]
                    }
                }
                val gain = if (mode == SpatialMode.VIRTUAL_ROOM) roomGain else OUTPUT_GAIN
                interleaved[2 * f] = outL * gain
                interleaved[2 * f + 1] = outR * gain
            }
        }
    }

    fun reset() {
        leftSpeaker.reset(); rightSpeaker.reset(); crossLowPass.reset(); crossDelay.reset()
        reflections.forEach { it.source.reset(); it.delay.reset() }
    }

    private fun updateAngles() {
        leftSpeaker.azimuthDegrees = -speakerAngle - headYaw
        rightSpeaker.azimuthDegrees = speakerAngle - headYaw
        reflections.forEach { it.source.azimuthDegrees = it.baseAzimuth - headYaw }
    }

    private data class Reflection(val delaySeconds: Double, val gainDb: Double, val azimuth: Double)

    private inner class BuiltReflection(val delay: DelayLine, val gain: Float, val source: BinauralSource, val baseAzimuth: Double)

    private fun Reflection.build() = BuiltReflection(
        DelayLine((delaySeconds * sampleRate).toInt(), 1),
        dbToLinear(gainDb).toFloat(),
        BinauralSource(sampleRate, azimuth),
        azimuth,
    )

    companion object {
        /** Keeps summed virtual-speaker output from clipping: two sources at ~unity -> -6 dB. */
        private const val OUTPUT_GAIN = 0.5f
    }
}

/** Fixed integer delay per channel. */
class DelayLine(val length: Int, channels: Int) {
    private val buffers = Array(channels) { FloatArray(length.coerceAtLeast(1)) }
    private val positions = IntArray(channels)

    /** Writes [x] and returns the sample written [length] calls ago. */
    fun push(x: Float, channel: Int): Float {
        if (length <= 0) return x
        val buf = buffers[channel]; val p = positions[channel]
        val out = buf[p]; buf[p] = x
        positions[channel] = (p + 1) % length
        return out
    }

    fun reset() { buffers.forEach { it.fill(0f) }; positions.fill(0) }
}
