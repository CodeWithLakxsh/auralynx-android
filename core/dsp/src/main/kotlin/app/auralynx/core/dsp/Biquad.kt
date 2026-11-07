package app.auralynx.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR section using the RBJ "Audio EQ Cookbook" formulas (Robert Bristow-Johnson).
 * Transposed direct form II, one state pair per channel.
 */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
    channels: Int,
) {
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    fun process(x: Float, channel: Int = 0): Float {
        val y = b0 * x + z1[channel]
        z1[channel] = b1 * x - a1 * y + z2[channel]
        z2[channel] = b2 * x - a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1.fill(0.0); z2.fill(0.0)
    }

    /** Magnitude response in dB at [frequency] for [sampleRate]. */
    fun magnitudeDb(frequency: Double, sampleRate: Double): Double {
        val w = 2 * PI * frequency / sampleRate
        val cw = cos(w); val sw = sin(w); val c2w = cos(2 * w); val s2w = sin(2 * w)
        val numRe = b0 + b1 * cw + b2 * c2w
        val numIm = -(b1 * sw + b2 * s2w)
        val denRe = 1 + a1 * cw + a2 * c2w
        val denIm = -(a1 * sw + a2 * s2w)
        val mag = sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm))
        return 20 * log10(mag)
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double, channels: Int) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0, channels)

        fun peaking(freq: Double, gainDb: Double, q: Double, sampleRate: Double, channels: Int = 2): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            return make(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a, channels)
        }

        fun lowShelf(freq: Double, gainDb: Double, sampleRate: Double, slope: Double = 1.0, channels: Int = 2): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
            val c = cos(w); val sa = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa, channels,
            )
        }

        fun highShelf(freq: Double, gainDb: Double, sampleRate: Double, slope: Double = 1.0, channels: Int = 2): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
            val c = cos(w); val sa = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa, channels,
            )
        }

        fun highPass(freq: Double, q: Double, sampleRate: Double, channels: Int = 1): Biquad {
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q); val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha, channels)
        }

        fun lowPass(freq: Double, q: Double, sampleRate: Double, channels: Int = 1): Biquad {
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q); val c = cos(w)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha, channels)
        }
    }
}
