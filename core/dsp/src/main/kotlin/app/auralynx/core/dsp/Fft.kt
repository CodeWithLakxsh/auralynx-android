package app.auralynx.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 Cooley-Tukey FFT. [re] and [im] must have the same power-of-two length. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * if (inverse) 1 else -1
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
}

/** Log-spaced magnitude bands (dBFS) from a block of mono samples, for spectrum displays. */
object SpectrumAnalyzer {
    fun bands(samples: FloatArray, sampleRate: Int, bandCount: Int = 24, fftSize: Int = 2048): FloatArray {
        val re = DoubleArray(fftSize); val im = DoubleArray(fftSize)
        val window = Fft.hann(fftSize)
        val n = minOf(fftSize, samples.size)
        for (i in 0 until n) re[i] = samples[i] * window[i]
        Fft.transform(re, im)
        val out = FloatArray(bandCount)
        val minF = 30.0; val maxF = minOf(16000.0, sampleRate / 2.0)
        for (b in 0 until bandCount) {
            val f0 = minF * Math.pow(maxF / minF, b.toDouble() / bandCount)
            val f1 = minF * Math.pow(maxF / minF, (b + 1).toDouble() / bandCount)
            val k0 = (f0 * fftSize / sampleRate).toInt().coerceIn(1, fftSize / 2 - 1)
            val k1 = (f1 * fftSize / sampleRate).toInt().coerceIn(k0 + 1, fftSize / 2)
            var peak = 0.0
            for (k in k0 until k1) peak = maxOf(peak, Math.hypot(re[k], im[k]))
            // Hann window coherent gain is 0.5; normalise so a full-scale sine reads ~0 dBFS.
            val mag = peak / (fftSize / 4.0)
            out[b] = (20 * Math.log10(mag + 1e-9)).toFloat()
        }
        return out
    }
}
