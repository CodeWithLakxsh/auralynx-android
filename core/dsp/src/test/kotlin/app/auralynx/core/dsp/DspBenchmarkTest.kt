package app.auralynx.core.dsp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Processing-cost benchmark on the build host (JVM). Results are printed for
 * docs/research/SPATIAL_AUDIO.md and are NOT phone measurements; the app measures real
 * on-device DSP load at runtime (Spatial audio screen → Measured performance).
 */
class DspBenchmarkTest {
    @Test fun processingIsFasterThanRealTime() {
        val fs = 48_000.0
        val seconds = 10
        val frames = (fs * seconds).toInt()
        val block = 1024
        val data = FloatArray(block * 2) { (Math.sin(it * 0.01) * 0.5).toFloat() }
        for (mode in SpatialMode.entries) {
            val eq = GraphicEqualizer(fs).also { it.setGains(EqPreset.MUSIC.gainsDb, EqPreset.MUSIC.preampDb) }
            val sp = StereoSpatializer(fs).also { it.mode = mode }
            repeat(50) { eq.process(data, block); sp.process(data, block) } // warm-up
            val t0 = System.nanoTime()
            var done = 0
            while (done < frames) { eq.process(data, block); sp.process(data, block); done += block }
            val elapsed = (System.nanoTime() - t0) / 1e9
            val load = elapsed / seconds
            println("BENCH mode=$mode eq+spatial load=${"%.4f".format(load)} x realtime, latency=${sp.algorithmicLatencyFrames} frames (${"%.2f".format(sp.algorithmicLatencyFrames * 1000 / fs)} ms)")
            assertTrue("$mode load $load", load < 0.5)
        }
    }
}
