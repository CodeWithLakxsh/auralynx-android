package app.auralynx.core.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import app.auralynx.core.common.Diag
import app.auralynx.core.dsp.GraphicEqualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** An audio session another app announced via ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION. */
data class ExternalSession(val sessionId: Int, val packageName: String?, val engine: String)

/**
 * Applies the active EQ profile to audio sessions that *other* players publish with
 * [AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION].
 *
 * This is the only supported Android mechanism for a third-party equalizer. It is **not**
 * system-wide: it only reaches players that broadcast their session, and effects live only as
 * long as the Auralynx process does (in practice, while the accessory monitor service runs).
 */
@Singleton
class SessionEffectsController @Inject constructor() {
    private val effects = ConcurrentHashMap<Int, AudioEffect>()
    private val _sessions = MutableStateFlow<List<ExternalSession>>(emptyList())
    val sessions: StateFlow<List<ExternalSession>> = _sessions.asStateFlow()

    @Volatile private var gains: List<Double> = List(10) { 0.0 }
    @Volatile private var preamp = 0.0
    @Volatile var enabled: Boolean = false
        private set

    fun setEnabled(on: Boolean) {
        enabled = on
        effects.values.forEach { runCatching { it.enabled = on } }
    }

    fun setProfile(gainsDb: List<Double>, preampDb: Double) {
        gains = gainsDb; preamp = preampDb
        effects.values.forEach { apply(it) }
    }

    fun onSessionOpened(sessionId: Int, packageName: String?) {
        if (sessionId <= 0 || effects.containsKey(sessionId)) return
        val effect = runCatching { create(sessionId) }.getOrElse { e ->
            Diag.w(TAG, e) { "Could not attach effect to session $sessionId" }
            return
        }
        effects[sessionId] = effect
        apply(effect)
        runCatching { effect.enabled = enabled }
        _sessions.update { it + ExternalSession(sessionId, packageName, if (effect is DynamicsProcessing) "DynamicsProcessing" else "Equalizer") }
    }

    fun onSessionClosed(sessionId: Int) {
        effects.remove(sessionId)?.let { runCatching { it.release() } }
        _sessions.update { list -> list.filterNot { it.sessionId == sessionId } }
    }

    fun releaseAll() {
        effects.keys.toList().forEach { onSessionClosed(it) }
    }

    private fun create(sessionId: Int): AudioEffect = if (Build.VERSION.SDK_INT >= 28) {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, BANDS, false, 0, false, 0, true,
        ).build()
        DynamicsProcessing(0, sessionId, cfg)
    } else {
        Equalizer(0, sessionId)
    }

    private fun apply(effect: AudioEffect) {
        runCatching {
            when (effect) {
                is DynamicsProcessing -> {
                    val centres = GraphicEqualizer.BANDS
                    val eq = DynamicsProcessing.Eq(true, true, BANDS)
                    for (i in 0 until BANDS) {
                        // Band i covers up to the geometric midpoint between centre i and i+1.
                        val cutoff = if (i < BANDS - 1) sqrt(centres[i] * centres[i + 1]) else 20_000.0
                        eq.setBand(i, DynamicsProcessing.EqBand(true, cutoff.toFloat(), (gains[i] + preamp).toFloat()))
                    }
                    effect.setPreEqAllChannelsTo(eq)
                    effect.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
                }
                is Equalizer -> {
                    val range = effect.bandLevelRange // millibels
                    for (b in 0 until effect.numberOfBands) {
                        val centreHz = effect.getCenterFreq(b.toShort()) / 1000.0
                        val nearest = GraphicEqualizer.BANDS.indices.minByOrNull { kotlin.math.abs(GraphicEqualizer.BANDS[it] - centreHz) } ?: 0
                        val mb = ((gains[nearest] + preamp) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                        effect.setBandLevel(b.toShort(), mb.toShort())
                    }
                }
            }
        }.onFailure { Diag.w(TAG, it) { "Applying EQ failed" } }
    }

    companion object {
        private const val TAG = "SessionEffects"
        private const val BANDS = 10
    }
}
