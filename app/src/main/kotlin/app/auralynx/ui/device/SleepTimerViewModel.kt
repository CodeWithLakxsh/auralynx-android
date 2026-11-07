package app.auralynx.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.system.MediaControl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pauses media after a delay via the public media-key API. Runs in the app scope. */
@HiltViewModel
class SleepTimerViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val media: MediaControl,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    val endsAt = settings.settings.map { it.sleepTimerEndsAtMillis }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun start(minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        appScope.launch { settings.update { it.copy(sleepTimerEndsAtMillis = end) } }
        job?.cancel()
        job = appScope.launch {
            delay(minutes * 60_000L)
            media.pause()
            settings.update { it.copy(sleepTimerEndsAtMillis = null) }
        }
    }

    fun cancel() {
        job?.cancel()
        appScope.launch { settings.update { it.copy(sleepTimerEndsAtMillis = null) } }
    }

    companion object { @Volatile private var job: Job? = null }
}
