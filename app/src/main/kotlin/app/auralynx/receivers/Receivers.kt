package app.auralynx.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import app.auralynx.automation.AutomationRunner
import app.auralynx.core.audio.SessionEffectsController
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.service.AccessoryCoordinator
import dagger.hilt.android.AndroidEntryPoint
import app.auralynx.core.common.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wakes Auralynx when an accessory connects (ACL / A2DP / HFP system broadcasts, which are exempt
 * from implicit-broadcast limits). The coordinator then reads the actual state from profile proxies.
 */
@AndroidEntryPoint
class BluetoothEventReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: AccessoryCoordinator
    override fun onReceive(context: Context, intent: Intent) {
        coordinator.start()
    }
}

/** Players announce their audio sessions; Auralynx attaches its EQ when the user enabled it. */
@AndroidEntryPoint
class AudioEffectSessionReceiver : BroadcastReceiver() {
    @Inject lateinit var effects: SessionEffectsController
    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> effects.onSessionOpened(session, pkg)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> effects.onSessionClosed(session)
        }
    }
}

@AndroidEntryPoint
class AutomationAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var runner: AutomationRunner
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try { runner.evaluateNow() } finally { pending.finish() }
        }
    }
}
