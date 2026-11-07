package app.auralynx.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.common.Diag
import app.auralynx.core.dsp.GraphicEqualizer
import app.auralynx.core.dsp.SpatialMode
import app.auralynx.core.dsp.SpectrumAnalyzer
import app.auralynx.core.dsp.StereoSpatializer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerState(
    val uri: Uri? = null,
    val title: String? = null,
    val playing: Boolean = false,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val sampleRate: Int = 0,
    /** Size of the AudioTrack output buffer in milliseconds (an upper bound on buffering delay). */
    val outputBufferMillis: Double? = null,
    val dspLatencyMillis: Double = 0.0,
    /** Measured average DSP processing cost per buffer as a fraction of real time. */
    val dspLoad: Double = 0.0,
    val spectrum: FloatArray = FloatArray(0),
    val error: String? = null,
)

/**
 * App-local playback pipeline: MediaExtractor -> MediaCodec (PCM 16-bit) -> Auralynx DSP
 * (graphic EQ + spatialiser) -> AudioTrack (float).
 *
 * Processing applies **only to audio played inside Auralynx**. It is the reference path for the
 * equalizer and spatial-audio modules; other apps are unaffected.
 */
@Singleton
class LocalAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile private var gains: List<Double> = List(10) { 0.0 }
    @Volatile private var preamp: Double = 0.0
    @Volatile private var spatialMode: SpatialMode = SpatialMode.OFF
    @Volatile private var headYaw: Double = 0.0
    @Volatile private var paused = false
    @Volatile private var seekToMillis: Long? = null
    private var job: Job? = null

    fun setEqualizer(gainsDb: List<Double>, preampDb: Double) { gains = gainsDb; preamp = preampDb }
    fun setSpatialMode(mode: SpatialMode) { spatialMode = mode }
    fun setHeadYaw(degrees: Double) { headYaw = degrees }

    fun play(uri: Uri, title: String?) {
        stop()
        paused = false
        _state.value = PlayerState(uri = uri, title = title, playing = true)
        job = scope.launch(Dispatchers.IO) { runCatching { decodeLoop(uri) }.onFailure { e ->
            Diag.e(TAG, e) { "Playback failed" }
            _state.update { it.copy(playing = false, error = e.message ?: "Playback failed") }
        } }
    }

    fun togglePause() {
        if (_state.value.uri == null) return
        paused = !paused
        _state.update { it.copy(playing = !paused) }
    }

    fun seekTo(millis: Long) { seekToMillis = millis }

    fun stop() {
        job?.cancel(); job = null
        _state.update { it.copy(playing = false) }
    }

    private suspend fun decodeLoop(uri: Uri) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track found")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val inChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
        _state.update { it.copy(sampleRate = sampleRate, durationMillis = duration) }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val eq = GraphicEqualizer(sampleRate.toDouble())
        val spatial = StereoSpatializer(sampleRate.toDouble())
        var appliedGains: List<Double>? = null
        var appliedPreamp = Double.NaN
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var framesWritten = 0L
        var dspNanos = 0L
        var audioNanos = 0L
        var lastUiUpdate = 0L

        try {
            while (currentCoroutineContext().isActive) {
                if (paused) { track.pause(); kotlinx.coroutines.delay(50); continue } else if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                seekToMillis?.let { target ->
                    extractor.seekTo(target * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush(); track.flush(); eq.reset(); spatial.reset()
                    framesWritten = target * sampleRate / 1000
                    inputDone = false; seekToMillis = null
                }
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0); extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                    val shorts = info.size / 2
                    val frames = shorts / inChannels
                    val stereo = FloatArray(frames * 2)
                    val sb = out.asShortBuffer()
                    for (f in 0 until frames) {
                        val l = sb.get(f * inChannels) / 32768f
                        val r = if (inChannels > 1) sb.get(f * inChannels + 1) / 32768f else l
                        stereo[2 * f] = l; stereo[2 * f + 1] = r
                    }
                    codec.releaseOutputBuffer(outIndex, false)

                    val g = gains; val p = preamp
                    if (g != appliedGains || p != appliedPreamp) { eq.setGains(g, p); appliedGains = g; appliedPreamp = p }
                    spatial.mode = spatialMode
                    spatial.headYaw = headYaw
                    val t0 = System.nanoTime()
                    eq.process(stereo, frames)
                    spatial.process(stereo, frames)
                    for (i in stereo.indices) stereo[i] = stereo[i].coerceIn(-1f, 1f)
                    dspNanos += System.nanoTime() - t0
                    audioNanos += frames * 1_000_000_000L / sampleRate

                    track.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING)
                    framesWritten += frames

                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate > 100) {
                        lastUiUpdate = now
                        val mono = FloatArray(minOf(frames, 2048)) { (stereo[2 * it] + stereo[2 * it + 1]) * 0.5f }
                        val latencyMs = outputBufferMillis(track, sampleRate)
                        _state.update {
                            it.copy(
                                positionMillis = framesWritten * 1000 / sampleRate,
                                spectrum = SpectrumAnalyzer.bands(mono, sampleRate),
                                outputBufferMillis = latencyMs,
                                dspLatencyMillis = spatial.algorithmicLatencyFrames * 1000.0 / sampleRate,
                                dspLoad = if (audioNanos > 0) dspNanos.toDouble() / audioNanos else 0.0,
                            )
                        }
                    }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            runCatching { track.stop(); track.release() }
            runCatching { codec.stop(); codec.release() }
            extractor.release()
            _state.update { it.copy(playing = false) }
        }
    }

    private fun outputBufferMillis(track: AudioTrack, sampleRate: Int): Double =
        track.bufferSizeInFrames * 1000.0 / sampleRate

    companion object { private const val TAG = "LocalAudioPlayer" }
}
