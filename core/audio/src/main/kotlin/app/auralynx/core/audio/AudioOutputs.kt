package app.auralynx.core.audio

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Platform spatial-audio facts reported by android.media.Spatializer (Android 13+). */
data class PlatformSpatializerInfo(
    val apiAvailable: Boolean,
    val available: Boolean,
    val enabled: Boolean,
    val headTrackerAvailable: Boolean,
    val level: Int?,
)

@Singleton
class AudioOutputs @Inject constructor(@ApplicationContext private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)

    fun bluetoothOutputActive(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** Media volume as a fraction of maximum, observed through the system settings provider (no polling). */
    fun mediaVolumeFraction(): Flow<Float> = callbackFlow {
        fun read() = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        trySend(read())
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(read()) }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    /** Whether any app is playing media, from AudioManager's playback callback (event-driven, no polling). */
    fun mediaPlaying(): Flow<Boolean> = callbackFlow {
        fun active(configs: List<android.media.AudioPlaybackConfiguration>) = configs.any {
            it.audioAttributes.usage == android.media.AudioAttributes.USAGE_MEDIA || it.audioAttributes.usage == android.media.AudioAttributes.USAGE_GAME
        }
        trySend(active(am.activePlaybackConfigurations))
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) { trySend(active(configs)) }
        }
        am.registerAudioPlaybackCallback(cb, Handler(Looper.getMainLooper()))
        awaitClose { am.unregisterAudioPlaybackCallback(cb) }
    }.distinctUntilChanged()

    fun platformSpatializer(): PlatformSpatializerInfo {
        if (Build.VERSION.SDK_INT < 32) return PlatformSpatializerInfo(false, false, false, false, null)
        val s: Spatializer = am.spatializer
        return PlatformSpatializerInfo(
            apiAvailable = true,
            available = s.isAvailable,
            enabled = s.isEnabled,
            headTrackerAvailable = Build.VERSION.SDK_INT >= 33 && s.isHeadTrackerAvailable,
            level = s.immersiveAudioLevel,
        )
    }
}
