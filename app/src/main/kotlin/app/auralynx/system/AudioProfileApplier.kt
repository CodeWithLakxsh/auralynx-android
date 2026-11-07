package app.auralynx.system

import app.auralynx.core.audio.LocalAudioPlayer
import app.auralynx.core.audio.SessionEffectsController
import app.auralynx.core.data.repo.AudioProfileRepository
import app.auralynx.core.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Applies a saved audio profile to the app-local player and to published external sessions. */
@Singleton
class AudioProfileApplier @Inject constructor(
    private val profiles: AudioProfileRepository,
    private val settings: SettingsRepository,
    private val player: LocalAudioPlayer,
    private val sessions: SessionEffectsController,
) {
    suspend fun apply(profileId: Long): Boolean {
        val p = profiles.get(profileId) ?: return false
        player.setEqualizer(p.gainsDb, p.preampDb)
        player.setSpatialMode(p.spatialMode)
        sessions.setProfile(p.gainsDb, p.preampDb)
        settings.update { it.copy(sessionEqProfileId = profileId) }
        return true
    }

    suspend fun restore() {
        settings.current().sessionEqProfileId?.let { apply(it) }
    }
}
