package app.auralynx

import android.app.Application
import android.util.Log
import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.common.Diag
import app.auralynx.core.data.repo.DiagnosticsRepository
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.service.AccessoryCoordinator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AuralynxApp : Application() {
    @Inject lateinit var coordinator: AccessoryCoordinator
    @Inject lateinit var diagnostics: DiagnosticsRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Diag.addSink { e -> Log.println(priority(e.level), "Auralynx/" + e.tag, e.message) }
        }
        // The on-device diagnostic log is opt-in.
        scope.launch {
            settings.settings.map { it.diagnosticsLoggingEnabled }.distinctUntilChanged().collect { on ->
                if (on) Diag.addSink(diagnostics) else Diag.removeSink(diagnostics)
            }
        }
        coordinator.start()
    }

    private fun priority(level: Diag.Level) = when (level) {
        Diag.Level.VERBOSE -> Log.VERBOSE
        Diag.Level.DEBUG -> Log.DEBUG
        Diag.Level.INFO -> Log.INFO
        Diag.Level.WARN -> Log.WARN
        Diag.Level.ERROR -> Log.ERROR
    }
}
