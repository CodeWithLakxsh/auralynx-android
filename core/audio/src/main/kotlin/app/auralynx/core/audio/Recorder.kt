package app.auralynx.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import app.auralynx.core.common.Diag
import app.auralynx.core.common.IoDispatcher
import app.auralynx.core.dsp.Levels
import app.auralynx.core.dsp.Wav
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

enum class RecordingInput {
    /** Built-in microphone; full quality. */
    PHONE,

    /**
     * The connected headset's microphone through the standard hands-free (HFP/SCO) route selected
     * with AudioManager.setCommunicationDevice. Narrow-band; music playback quality drops while active.
     */
    BLUETOOTH_HEADSET,
}

data class RecorderState(
    val recording: Boolean = false,
    val elapsedMillis: Long = 0,
    val levelDb: Double = -90.0,
    val input: RecordingInput = RecordingInput.PHONE,
    val platformNoiseSuppressor: Boolean = false,
    val error: String? = null,
)

data class RecordingResult(val file: File, val durationMillis: Long, val sampleRate: Int, val input: RecordingInput)

/**
 * Microphone recorder writing 16-bit mono WAV into app-private storage. It records only this
 * app's own microphone input; it cannot and does not capture calls or other apps' audio.
 * Must be driven from a foreground service of type `microphone`.
 */
@Singleton
class Recorder @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()
    @Volatile private var stopRequested = false

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun bluetoothInputAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && audioManager.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    fun requestStop() { stopRequested = true }

    /** Records until [requestStop]; suspends for the whole recording. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun record(target: File, input: RecordingInput, usePlatformNoiseSuppressor: Boolean): RecordingResult = withContext(io) {
        check(hasPermission()) { "Microphone permission not granted" }
        stopRequested = false
        val rate = if (input == RecordingInput.BLUETOOTH_HEADSET) 16_000 else 48_000
        var routed = false
        if (input == RecordingInput.BLUETOOTH_HEADSET && Build.VERSION.SDK_INT >= 31) {
            val sco = audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: error("No Bluetooth headset microphone available")
            routed = audioManager.setCommunicationDevice(sco)
        }
        val source = if (input == RecordingInput.BLUETOOTH_HEADSET) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        val ns = if (usePlatformNoiseSuppressor && NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true } else null
        val agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId)?.also { it.enabled = false } else null
        _state.value = RecorderState(recording = true, input = input, platformNoiseSuppressor = ns != null)

        target.parentFile?.mkdirs()
        val raf = RandomAccessFile(target, "rw")
        raf.setLength(0)
        raf.write(Wav.header(rate, 1, 0))
        var bytes = 0
        val buffer = ByteArray(minBuf)
        val start = System.currentTimeMillis()
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive && !stopRequested) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                raf.write(buffer, 0, n)
                bytes += n
                val level = Levels.rmsDb(Wav.fromPcm16(buffer, n))
                _state.value = _state.value.copy(elapsedMillis = System.currentTimeMillis() - start, levelDb = level)
            }
        } finally {
            runCatching { record.stop() }
            record.release(); ns?.release(); agc?.release()
            if (routed && Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
            raf.seek(0); raf.write(Wav.header(rate, 1, bytes)); raf.close()
            _state.value = RecorderState(input = input)
        }
        val duration = bytes / 2 * 1000L / rate
        Diag.i(TAG) { "Recording finished: ${duration} ms" }
        RecordingResult(target, duration, rate, input)
    }

    companion object { private const val TAG = "Recorder" }
}
