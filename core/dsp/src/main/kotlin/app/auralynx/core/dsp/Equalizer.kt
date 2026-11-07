package app.auralynx.core.dsp

/** Ten-band graphic equalizer with ISO octave centres. Gains are in dB, clamped to ±[MAX_GAIN_DB]. */
class GraphicEqualizer(private val sampleRate: Double, private val channels: Int = 2) {

    private var gains = DoubleArray(BANDS.size)
    private var preampDb = 0.0
    private var filters: List<Biquad> = build()
    private var preampLinear = 1f

    fun setGains(gainsDb: List<Double>, preampDb: Double = 0.0) {
        require(gainsDb.size == BANDS.size) { "Expected ${BANDS.size} band gains" }
        gains = DoubleArray(BANDS.size) { gainsDb[it].coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) }
        this.preampDb = preampDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        preampLinear = dbToLinear(this.preampDb).toFloat()
        filters = build()
    }

    val isFlat: Boolean get() = gains.all { it == 0.0 } && preampDb == 0.0

    /** Processes interleaved samples in place. */
    fun process(interleaved: FloatArray, frames: Int = interleaved.size / channels) {
        if (isFlat) return
        for (f in 0 until frames) for (c in 0 until channels) {
            val i = f * channels + c
            var s = interleaved[i] * preampLinear
            for (filter in filters) s = filter.process(s, c)
            interleaved[i] = s
        }
    }

    /** Combined magnitude response in dB, for drawing the EQ curve. */
    fun responseDb(frequency: Double): Double = preampDb + filters.sumOf { it.magnitudeDb(frequency, sampleRate) }

    fun reset() = filters.forEach { it.reset() }

    private fun build(): List<Biquad> = BANDS.mapIndexedNotNull { i, f ->
        val g = gains[i]
        when {
            g == 0.0 -> null
            i == 0 -> Biquad.lowShelf(f, g, sampleRate, channels = channels)
            i == BANDS.lastIndex -> Biquad.highShelf(f, g, sampleRate, channels = channels)
            else -> Biquad.peaking(f, g, Q, sampleRate, channels)
        }
    }

    companion object {
        val BANDS: List<Double> = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        const val MAX_GAIN_DB = 12.0
        /** Q of ~1.41 gives one-octave bandwidth between adjacent bands. */
        private const val Q = 1.41
    }
}

/** Built-in tuning targets. Values are starting points, not measurements of any product. */
enum class EqPreset(val displayName: String, val gainsDb: List<Double>, val preampDb: Double = 0.0) {
    FLAT("Flat", List(10) { 0.0 }),
    BASS_BOOST("Bass Boost", listOf(7.0, 6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0), -5.0),
    VOCAL("Vocal Enhancement", listOf(-2.0, -2.0, -1.0, 0.0, 1.5, 3.0, 3.5, 2.5, 1.0, 0.0), -2.5),
    MUSIC("Music", listOf(3.0, 2.5, 1.0, 0.0, -1.0, -0.5, 0.5, 1.5, 2.5, 3.0), -2.5),
    GAMING("Gaming", listOf(2.0, 1.5, 0.0, -1.0, -1.0, 0.5, 2.5, 3.5, 3.0, 1.5), -3.0),
    MOVIE("Movie", listOf(4.0, 3.5, 2.0, 0.0, 0.0, 1.0, 2.0, 1.5, 1.0, 0.5), -3.5),
    PODCAST("Podcast", listOf(-4.0, -3.0, -1.0, 0.5, 2.0, 3.0, 3.0, 2.0, 0.0, -1.5), -2.0),
}

internal fun dbToLinear(db: Double): Double = Math.pow(10.0, db / 20.0)
