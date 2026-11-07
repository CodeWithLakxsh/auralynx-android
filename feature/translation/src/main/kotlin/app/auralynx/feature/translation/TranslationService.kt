package app.auralynx.feature.translation

import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.Outcome
import app.auralynx.core.data.repo.TranslationHistoryRepository
import app.auralynx.core.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses an engine (on-device first, then the user's cloud server if they enabled one), runs the
 * translation, and saves history only when the user allows it.
 */
@Singleton
class TranslationService @Inject constructor(
    val system: SystemTranslationEngine,
    val cloud: LibreTranslateEngine,
    private val history: TranslationHistoryRepository,
    private val settings: SettingsRepository,
) {
    data class Result(val text: String, val engine: TranslationEngine)

    suspend fun pickEngine(source: String, target: String): Pair<TranslationEngine?, PairAvailability> {
        val local = system.availability(source, target)
        if (local == PairAvailability.READY) return system to local
        val remote = cloud.availability(source, target)
        if (remote == PairAvailability.READY) return cloud to remote
        return null to local
    }

    suspend fun translate(text: String, source: String, target: String): Outcome<Result> {
        if (source == target) return Outcome.Success(Result(text, system))
        val (engine, _) = pickEngine(source, target)
        engine ?: return Outcome.Failure(FailureReason.NOT_CONFIGURED)
        return when (val r = engine.translate(text, source, target)) {
            is Outcome.Success -> {
                if (settings.current().translationSaveHistory) history.add(source, target, text, r.value, engine.id)
                Outcome.Success(Result(r.value, engine))
            }
            is Outcome.Failure -> r
        }
    }
}
