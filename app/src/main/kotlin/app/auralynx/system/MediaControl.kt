package app.auralynx.system

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Media transport control through the public AudioManager media-key API. */
@Singleton
class MediaControl @Inject constructor(@ApplicationContext context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)

    val isMusicActive: Boolean get() = am.isMusicActive

    fun playPause() = key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun play() = key(KeyEvent.KEYCODE_MEDIA_PLAY)
    fun pause() = key(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun next() = key(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}
