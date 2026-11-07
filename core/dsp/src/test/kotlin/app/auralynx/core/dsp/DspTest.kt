package app.auralynx.core.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class DspTest {
    private val fs = 48_000.0

    private fun sine(freq: Double, seconds: Double, amp: Double = 0.5) =
        FloatArray((fs * seconds).toInt()) { (amp * sin(2 * PI * freq * it / fs)).toFloat() }

    @Test fun peakingFilter_hasRequestedGainAtCentre() {
        val f = Biquad.peaking(1000.0, 6.0, 1.41, fs)
        assertEquals(6.0, f.magnitudeDb(1000.0, fs), 0.01)
        assertEquals(0.0, f.magnitudeDb(20.0, fs), 0.1)
    }

    @Test fun shelves_reachGainAtExtremes() {
        assertEquals(6.0, Biquad.lowShelf(100.0, 6.0, fs).magnitudeDb(10.0, fs), 0.2)
        assertEquals(-6.0, Biquad.highShelf(8000.0, -6.0, fs).magnitudeDb(20000.0, fs), 0.4)
    }

    @Test fun filterProcessing_matchesAnalyticResponse() {
        val f = Biquad.peaking(1000.0, 9.0, 1.41, fs, channels = 1)
        val input = sine(1000.0, 0.5)
        val out = FloatArray(input.size) { f.process(input[it]) }
        val measured = Levels.rmsDb(out.copyOfRange(4800, out.size)) - Levels.rmsDb(input.copyOfRange(4800, input.size))
        assertEquals(9.0, measured, 0.2)
    }

    @Test fun flatEqualizer_isBitTransparent() {
        val eq = GraphicEqualizer(fs)
        val data = sine(440.0, 0.01).flatMap { listOf(it, -it) }.toFloatArray()
        val copy = data.copyOf()
        eq.process(data)
        assertArrayEquals(copy, data, 0f)
        assertEquals(0.0, eq.responseDb(1000.0), 1e-9)
    }

    @Test fun equalizer_responseFollowsPresetShape() {
        val eq = GraphicEqualizer(fs)
        eq.setGains(EqPreset.BASS_BOOST.gainsDb, EqPreset.BASS_BOOST.preampDb)
        assertTrue(eq.responseDb(60.0) > eq.responseDb(4000.0) + 6)
        eq.setGains(List(10) { 50.0 })
        assertTrue("gains are clamped", eq.responseDb(1000.0) < 40)
    }

    @Test fun presets_allHaveTenBands() {
        EqPreset.entries.forEach { assertEquals(it.name, 10, it.gainsDb.size) }
    }

    @Test fun fft_matchesNaiveDft() {
        val n = 64
        val rnd = Random(7)
        val x = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val re = x.copyOf(); val im = DoubleArray(n)
        Fft.transform(re, im)
        for (k in 0 until n) {
            var r = 0.0; var i = 0.0
            for (t in 0 until n) { r += x[t] * cos(2 * PI * k * t / n); i -= x[t] * sin(2 * PI * k * t / n) }
            assertEquals(r, re[k], 1e-9); assertEquals(i, im[k], 1e-9)
        }
        Fft.transform(re, im, inverse = true)
        for (t in 0 until n) assertEquals(x[t], re[t], 1e-9)
    }

    @Test fun spectrum_findsToneBand() {
        val bands = SpectrumAnalyzer.bands(sine(1000.0, 0.1, amp = 1.0), 48_000)
        val loudest = bands.indices.maxByOrNull { bands[it] }!!
        val f0 = 30.0 * Math.pow(16000.0 / 30.0, loudest / 24.0)
        val f1 = 30.0 * Math.pow(16000.0 / 30.0, (loudest + 1) / 24.0)
        assertTrue("1 kHz lies in band [$f0, $f1)", 1000.0 >= f0 * 0.98 && 1000.0 < f1 * 1.02)
        assertEquals(0.0, bands[loudest].toDouble(), 1.5)
    }

    @Test fun binaural_sourceOnRightArrivesEarlierAndLouderAtRightEar() {
        val src = BinauralSource(fs, azimuthDegrees = 90.0)
        val impulse = FloatArray(256).also { it[0] = 1f }
        val l = FloatArray(256); val r = FloatArray(256); val o = FloatArray(2)
        impulse.forEachIndexed { i, x -> src.process(x, o); l[i] = o[0]; r[i] = o[1] }
        val firstL = l.indexOfFirst { abs(it) > 1e-3 }
        val firstR = r.indexOfFirst { abs(it) > 1e-3 }
        assertTrue("ITD: right=$firstR left=$firstL", firstR < firstL)
        // Woodworth ITD for 90 degrees ~ 0.66 ms -> ~31 frames at 48 kHz
        assertEquals(31.0, (firstL - firstR).toDouble(), 3.0)
        assertTrue(Levels.rms(r) > Levels.rms(l))
    }

    @Test fun binaural_frontalSourceIsSymmetric() {
        val src = BinauralSource(fs, azimuthDegrees = 0.0)
        val o = FloatArray(2)
        val noise = Random(3)
        repeat(500) {
            src.process(noise.nextFloat() - 0.5f, o)
            assertEquals(o[0], o[1], 1e-5f)
        }
    }

    @Test fun spatializer_offIsTransparent_andReportsLatency() {
        val s = StereoSpatializer(fs)
        val data = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        s.process(data)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), data, 0f)
        assertEquals(0, s.algorithmicLatencyFrames)
        s.mode = SpatialMode.VIRTUAL_SPEAKERS
        assertEquals(64, s.algorithmicLatencyFrames) // 1.33 ms at 48 kHz
    }

    @Test fun spatializer_doesNotClipFullScaleInput() {
        for (mode in SpatialMode.entries) {
            val s = StereoSpatializer(fs).also { it.mode = mode }
            val data = sine(200.0, 0.2, amp = 0.9).flatMap { listOf(it, it) }.toFloatArray()
            s.process(data)
            assertTrue("$mode peak=${Levels.peak(data)}", Levels.peak(data) <= 1.0f)
        }
    }

    @Test fun headYaw_rotatesStage() {
        val s = StereoSpatializer(fs).also { it.mode = SpatialMode.VIRTUAL_SPEAKERS }
        // Mono content: head turned right by 60 degrees should make the left ear louder.
        s.headYaw = 60.0
        val data = sine(2000.0, 0.2).flatMap { listOf(it, it) }.toFloatArray()
        s.process(data)
        val left = FloatArray(data.size / 2) { data[2 * it] }
        val right = FloatArray(data.size / 2) { data[2 * it + 1] }
        assertTrue(Levels.rms(left) > Levels.rms(right) * 1.2)
    }

    @Test fun noiseReducer_improvesSnr() {
        val rnd = Random(11)
        val clean = FloatArray(48_000) { i ->
            // speech-like bursts: tone present only in the middle third
            if (i in 16_000 until 32_000) (0.4 * sin(2 * PI * 440 * i / fs)).toFloat() else 0f
        }
        val noise = FloatArray(clean.size) { (gaussian(rnd) * 0.03).toFloat() }
        val noisy = FloatArray(clean.size) { clean[it] + noise[it] }
        val out = SpectralNoiseReducer().process(noisy)
        val residualBefore = Levels.rms(noisy.copyOfRange(2_000, 14_000))
        val residualAfter = Levels.rms(out.copyOfRange(2_000, 14_000))
        assertTrue("noise floor reduced by >= 10 dB", 20 * Math.log10(residualBefore / residualAfter) >= 10)
        // Tone survives with less than 2 dB loss
        val toneBefore = Levels.rmsDb(noisy.copyOfRange(20_000, 28_000))
        val toneAfter = Levels.rmsDb(out.copyOfRange(20_000, 28_000))
        assertEquals(toneBefore, toneAfter, 2.0)
    }

    @Test fun voiceEnhancer_removesRumble() {
        val rumble = sine(30.0, 0.5)
        VoiceEnhancer(fs).process(rumble)
        assertTrue(Levels.rmsDb(rumble.copyOfRange(4800, rumble.size)) < Levels.rmsDb(sine(30.0, 0.5)) - 6)
    }

    @Test fun normalize_hitsTargetPeak() {
        val x = sine(100.0, 0.05, amp = 0.1)
        Levels.normalize(x, -1.0)
        assertEquals(Math.pow(10.0, -1.0 / 20), Levels.peak(x).toDouble(), 1e-3)
    }

    @Test fun wav_roundTrip() {
        val audio = Wav.Audio(16_000, 1, floatArrayOf(0f, 0.5f, -0.5f, 0.999f))
        val out = ByteArrayOutputStream().also { Wav.write(it, audio) }.toByteArray()
        assertEquals(44 + 8, out.size)
        val back = Wav.read(ByteArrayInputStream(out))
        assertEquals(16_000, back.sampleRate)
        assertEquals(1, back.channels)
        assertArrayEquals(audio.samples, back.samples, 1f / 16_000)
    }
}

/** Box-Muller transform; kotlin.random.Random has no Gaussian generator. */
private fun gaussian(r: Random): Double =
    kotlin.math.sqrt(-2.0 * kotlin.math.ln(1.0 - r.nextDouble())) * cos(2 * PI * r.nextDouble())
