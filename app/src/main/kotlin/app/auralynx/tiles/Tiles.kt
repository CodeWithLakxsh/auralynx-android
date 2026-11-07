package app.auralynx.tiles

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import app.auralynx.automation.AutomationRunner
import app.auralynx.core.automation.ActionTarget
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.system.label
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Cycles the listening mode. Unavailable (greyed out) unless the accessory channel is connected. */
@AndroidEntryPoint
class NoiseControlTileService : TileService() {
    @Inject lateinit var accessories: AccessoryManager
    @Inject lateinit var runner: AutomationRunner
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onStartListening() = refresh()

    override fun onClick() {
        val s = accessories.accessories.value.firstOrNull { it.capabilities.isOperable(Feature.NOISE_CONTROL) } ?: return refresh()
        val modes = s.model.listeningModes.toList().ifEmpty { return }
        val next = modes[(modes.indexOf(s.aap?.listeningMode) + 1).mod(modes.size)]
        scope.launch {
            accessories.setListeningMode(s.address, next)
            runner.noteManualChange(ActionTarget.LISTENING_MODE)
            refresh()
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val s = accessories.accessories.value.firstOrNull { it.capabilities.isOperable(Feature.NOISE_CONTROL) }
        tile.label = "Noise control"
        if (s == null) {
            tile.state = Tile.STATE_UNAVAILABLE
            setSubtitle(tile, if (accessories.accessories.value.isEmpty()) "Not connected" else "Not available")
        } else {
            tile.state = Tile.STATE_ACTIVE
            setSubtitle(tile, s.aap?.listeningMode?.label() ?: "Unknown")
        }
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, text: String) { if (Build.VERSION.SDK_INT >= 29) tile.subtitle = text }
}

@AndroidEntryPoint
class AutomationTileService : TileService() {
    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onStartListening() { scope.launch { render(settings.current().automationEnabled) } }

    override fun onClick() {
        scope.launch {
            settings.update { it.copy(automationEnabled = !it.automationEnabled) }
            render(settings.current().automationEnabled)
        }
    }

    private fun render(on: Boolean) {
        val tile = qsTile ?: return
        tile.label = "Smart automation"
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
