package app.auralynx.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.auralynx.core.audio.Recorder
import app.auralynx.core.audio.RecordingInput
import app.auralynx.core.common.Diag
import app.auralynx.core.data.db.RecordingEntity
import app.auralynx.core.data.repo.RecordingRepository
import app.auralynx.system.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Foreground service of type `microphone` that owns an active recording. */
@AndroidEntryPoint
class RecorderService : LifecycleService() {
    @Inject lateinit var recorder: Recorder
    @Inject lateinit var recordings: RecordingRepository
    @Inject lateinit var notifications: Notifications

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { recorder.requestStop(); return START_NOT_STICKY }
        if (recorder.state.value.recording) return START_NOT_STICKY
        if (!recorder.hasPermission()) { stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_RECORDER, notifications.recorder(), type) }
            .onFailure { Diag.w(TAG, it) { "Microphone service could not start" }; stopSelf(); return START_NOT_STICKY }

        val input = runCatching { RecordingInput.valueOf(intent?.getStringExtra(EXTRA_INPUT) ?: "") }.getOrDefault(RecordingInput.PHONE)
        val suppress = intent?.getBooleanExtra(EXTRA_NOISE_SUPPRESSOR, false) ?: false
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val file = File(filesDir, "recordings/rec-${System.currentTimeMillis()}.wav")
        lifecycleScope.launch {
            runCatching { recorder.record(file, input, suppress) }
                .onSuccess { r ->
                    recordings.add(RecordingEntity(0, "Recording $stamp", r.file.absolutePath, System.currentTimeMillis(), r.durationMillis, r.sampleRate, r.input.name))
                }
                .onFailure { Diag.e(TAG, it) { "Recording failed" }; file.delete() }
            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val ACTION_STOP = "app.auralynx.recorder.STOP"
        private const val EXTRA_INPUT = "input"
        private const val EXTRA_NOISE_SUPPRESSOR = "ns"

        fun start(context: Context, input: RecordingInput = RecordingInput.PHONE, platformNoiseSuppressor: Boolean = false) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RecorderService::class.java)
                    .putExtra(EXTRA_INPUT, input.name).putExtra(EXTRA_NOISE_SUPPRESSOR, platformNoiseSuppressor))
            }.onFailure { Diag.w(TAG, it) { "Recording can only start while Auralynx is in the foreground" } }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
