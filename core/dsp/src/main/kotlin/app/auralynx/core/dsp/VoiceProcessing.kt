package app.auralynx.core.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Speech clarity chain for recordings: rumble filter, presence lift, gentle compression. */
class VoiceEnhancer(sampleRate: Double) {
    private val highPass = Biquad.highPass(90.0, 0.707, sampleRate)
    private val mud = Biquad.peaking(250.0, -2.0, 1.0, sampleRate, 1)
    private val presence = Biquad.peaking(3000.0, 4.0, 1.0, sampleRate, 1)
    private val compressor = Compressor(sampleRate, thresholdDb = -22.0, ratio = 3.0, makeupDb = 5.0)

    fun process(mono: FloatArray) {
        for (i in mono.indices) {
            var s = highPass.process(mono[i])
            s = mud.process(s)
            s = presence.process(s)
            mono[i] = compressor.process(s).coerceIn(-1f, 1f)
        }
    }
}

/** Feed-forward peak compressor with attack/release smoothing. */
class Compressor(sampleRate: Double, private val thresholdDb: Double, private val ratio: Double, private val makeupDb: Double) {
    private val attack = exp(-1.0 / (0.005 * sampleRate))
    private val release = exp(-1.0 / (0.120 * sampleRate))
    private var envelope = 0.0
    private val makeup = dbToLinear(makeupDb)

    fun process(x: Float): Float {
        val level = abs(x.toDouble())
        envelope = if (level > envelope) attack * envelope + (1 - attack) * level else release * envelope + (1 - release) * level
        val levelDb = 20 * log10(envelope + 1e-9)
        val gainDb = if (levelDb > thresholdDb) (thresholdDb - levelDb) * (1 - 1 / ratio) else 0.0
        return (x * dbToLinear(gainDb) * makeup).toFloat()
    }
}

/**
 * Offline spectral-gating noise reduction (STFT, sqrt-Hann windows, 50 % overlap-add).
 *
 * The noise floor per frequency bin is estimated from the quietest 10 % of frames, so no separate
 * "noise sample" is needed. Gains are floored at [floorDb] to limit musical-noise artefacts.
 */
class SpectralNoiseReducer(
    private val fftSize: Int = 1024,
    private val reductionStrength: Double = 1.5,
    private val floorDb: Double = -18.0,
) {
    fun process(input: FloatArray): FloatArray {
        if (input.size < fftSize * 2) return input.copyOf()
        val hop = fftSize / 2
        val window = Fft.hann(fftSize).map { sqrt(it) }.toDoubleArray()
        val frameCount = (input.size - fftSize) / hop + 1
        val bins = fftSize / 2 + 1

        val spectraRe = Array(frameCount) { DoubleArray(fftSize) }
        val spectraIm = Array(frameCount) { DoubleArray(fftSize) }
        val energy = DoubleArray(frameCount)
        for (f in 0 until frameCount) {
            val re = spectraRe[f]; val im = spectraIm[f]
            for (i in 0 until fftSize) re[i] = input[f * hop + i] * window[i]
            Fft.transform(re, im)
            var e = 0.0
            for (k in 0 until bins) e += re[k] * re[k] + im[k] * im[k]
            energy[f] = e
        }

        val quiet = energy.indices.sortedBy { energy[it] }.take(max(1, frameCount / 10))
        val noise = DoubleArray(bins)
        for (f in quiet) for (k in 0 until bins) noise[k] += Math.hypot(spectraRe[f][k], spectraIm[f][k])
        for (k in 0 until bins) noise[k] /= quiet.size

        val floor = dbToLinear(floorDb)
        val previousGain = DoubleArray(bins) { 1.0 }
        val output = DoubleArray(input.size)
        for (f in 0 until frameCount) {
            val re = spectraRe[f]; val im = spectraIm[f]
            for (k in 0 until bins) {
                val mag = Math.hypot(re[k], im[k])
                val raw = if (mag <= 1e-12) floor else max(floor, 1 - reductionStrength * noise[k] / mag)
                // temporal smoothing reduces flicker between frames
                val g = 0.6 * raw + 0.4 * previousGain[k]
                previousGain[k] = g
                re[k] *= g; im[k] *= g
                if (k in 1 until fftSize / 2) { re[fftSize - k] = re[k]; im[fftSize - k] = -im[k] }
            }
            Fft.transform(re, im, inverse = true)
            for (i in 0 until fftSize) output[f * hop + i] += re[i] * window[i]
        }
        return FloatArray(input.size) { output[it].toFloat() }
    }
}

object Levels {
    fun rms(samples: FloatArray): Double = if (samples.isEmpty()) 0.0 else sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    fun rmsDb(samples: FloatArray): Double = 20 * log10(rms(samples) + 1e-12)
    fun peak(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f

    /** Scales so the absolute peak reaches [targetDb] dBFS. Silence is returned unchanged. */
    fun normalize(samples: FloatArray, targetDb: Double = -1.0) {
        val p = peak(samples)
        if (p < 1e-6f) return
        val g = (dbToLinear(targetDb) / p).toFloat()
        for (i in samples.indices) samples[i] *= g
    }
}
