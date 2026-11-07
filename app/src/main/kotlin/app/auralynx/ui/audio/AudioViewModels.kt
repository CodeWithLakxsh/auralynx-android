package app.auralynx.ui.audio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auralynx.core.audio.AudioOutputs
import app.auralynx.core.audio.AudioPostProcessor
import app.auralynx.core.audio.LocalAudioPlayer
import app.auralynx.core.audio.Recorder
import app.auralynx.core.audio.RecordingInput
import app.auralynx.core.audio.SessionEffectsController
import app.auralynx.core.data.db.RecordingEntity
import app.auralynx.core.data.repo.AudioProfile
import app.auralynx.core.data.repo.AudioProfileRepository
import app.auralynx.core.data.repo.RecordingRepository
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.core.dsp.EqPreset
import app.auralynx.core.dsp.GraphicEqualizer
import app.auralynx.core.dsp.SpatialMode
import app.auralynx.service.RecorderService
import app.auralynx.system.AudioProfileApplier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val profiles: AudioProfileRepository,
    private val applier: AudioProfileApplier,
    private val settings: SettingsRepository,
    val sessions: SessionEffectsController,
    val player: LocalAudioPlayer,
) : ViewModel() {
    val all = profiles.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedId = MutableStateFlow<Long?>(null)
    private val _draft = MutableStateFlow(AudioProfile(0, "Custom", EqPreset.FLAT.gainsDb, 0.0, SpatialMode.OFF, null))
    val draft = _draft.asStateFlow()
    val sessionEqEnabled = MutableStateFlow(sessions.enabled)
    val externalSessions = sessions.sessions

    init {
        viewModelScope.launch {
            profiles.ensureBuiltIns()
            settings.current().sessionEqProfileId?.let { id -> profiles.get(id)?.let { select(it) } }
        }
    }

    fun select(p: AudioProfile) { selectedId.value = p.id; _draft.value = p; preview() }

    fun setBand(i: Int, db: Double) { _draft.value = _draft.value.copy(gainsDb = _draft.value.gainsDb.toMutableList().also { it[i] = db }, builtIn = null); preview() }
    fun setPreamp(db: Double) { _draft.value = _draft.value.copy(preampDb = db, builtIn = null); preview() }

    /** The response curve the DSP actually implements, for drawing. */
    fun curve(points: Int = 64): List<Pair<Float, Float>> {
        val eq = GraphicEqualizer(48_000.0)
        eq.setGains(_draft.value.gainsDb, _draft.value.preampDb)
        return (0 until points).map { i ->
            val f = 20.0 * Math.pow(1000.0, i / (points - 1.0))
            (i / (points - 1f)) to ((eq.responseDb(f) + 15) / 30 * 100).toFloat().coerceIn(0f, 100f)
        }
    }

    private fun preview() {
        player.setEqualizer(_draft.value.gainsDb, _draft.value.preampDb)
        sessions.setProfile(_draft.value.gainsDb, _draft.value.preampDb)
    }

    fun saveAs(name: String) = viewModelScope.launch {
        val id = profiles.saveCustom(_draft.value.copy(id = if (_draft.value.builtIn == null) _draft.value.id else 0, name = name))
        applier.apply(id); selectedId.value = id
    }

    fun activate() = viewModelScope.launch { selectedId.value?.let { applier.apply(it) } }
    fun deleteSelected() = viewModelScope.launch { selectedId.value?.let { profiles.deleteCustom(it) } }
    fun setSessionEq(on: Boolean) { sessions.setEnabled(on); sessionEqEnabled.value = on }
}

@HiltViewModel
class SpatialViewModel @Inject constructor(val player: LocalAudioPlayer, private val outputs: AudioOutputs) : ViewModel() {
    val state = player.state
    val mode = MutableStateFlow(SpatialMode.OFF)
    val platform = outputs.platformSpatializer()
    val bluetoothOutput get() = outputs.bluetoothOutputActive()
    fun setMode(m: SpatialMode) { mode.value = m; player.setSpatialMode(m) }
    fun open(uri: Uri, title: String?) = player.play(uri, title)
    fun togglePause() = player.togglePause()
    fun stop() = player.stop()
}

@HiltViewModel
class RecorderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: Recorder,
    private val repo: RecordingRepository,
    private val post: AudioPostProcessor,
    val player: LocalAudioPlayer,
) : ViewModel() {
    val state = recorder.state
    val recordings = repo.recordings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val busy = MutableStateFlow<String?>(null)
    val bluetoothMicAvailable get() = recorder.bluetoothInputAvailable()

    fun start(input: RecordingInput, platformNs: Boolean) = RecorderService.start(context, input, platformNs)
    fun stop() = RecorderService.stop(context)

    fun process(r: RecordingEntity, noise: Boolean, voice: Boolean) = viewModelScope.launch {
        busy.value = "Processing…"
        runCatching { post.process(File(r.filePath), noise, voice, normalize = true) }.onSuccess { out ->
            repo.add(r.copy(id = 0, title = r.title + " (enhanced)", filePath = out.absolutePath, noiseReduced = noise, voiceEnhanced = voice))
        }
        busy.value = null
    }

    /** Exports to the share sheet as WAV or AAC (.m4a). */
    fun exportFile(r: RecordingEntity, aac: Boolean, onReady: (File) -> Unit) = viewModelScope.launch {
        busy.value = "Exporting…"
        val src = File(r.filePath)
        val out = runCatching {
            if (aac) post.exportAac(src, File(context.cacheDir, "exports/${src.nameWithoutExtension}.m4a").also { it.parentFile?.mkdirs() })
            else src.copyTo(File(context.cacheDir, "exports/${src.name}").also { it.parentFile?.mkdirs() }, overwrite = true)
        }.getOrNull()
        busy.value = null
        out?.let(onReady)
    }

    fun play(r: RecordingEntity) = player.play(Uri.fromFile(File(r.filePath)), r.title)
    fun delete(r: RecordingEntity) = viewModelScope.launch { repo.delete(r) }
}
