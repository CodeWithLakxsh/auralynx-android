package app.auralynx.service

import android.content.Context
import android.content.Intent
import android.os.Build
import app.auralynx.core.audio.AudioOutputs
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.core.bluetooth.AccessoryState
import app.auralynx.core.bluetooth.system.SystemBluetoothEvent
import app.auralynx.core.bluetooth.system.SystemBluetoothMonitor
import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.common.Clock
import app.auralynx.core.common.Diag
import app.auralynx.core.data.repo.AudioProfileRepository
import app.auralynx.core.data.repo.BatteryRepository
import app.auralynx.core.data.repo.ConnectionEventType
import app.auralynx.core.data.repo.DataMaintenance
import app.auralynx.core.data.repo.DeviceRepository
import app.auralynx.core.data.repo.GestureAction
import app.auralynx.core.data.repo.GestureRepository
import app.auralynx.core.data.repo.ListeningRepository
import app.auralynx.core.data.security.SecretStore
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.core.protocol.aap.AapEvent
import app.auralynx.core.protocol.devices.AccessoryModel
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.ChargingState
import app.auralynx.automation.AutomationRunner
import app.auralynx.system.AudioProfileApplier
import app.auralynx.system.LocationHelper
import app.auralynx.system.MediaControl
import app.auralynx.system.Notifications
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects accessory events to persistence, alerts, media control and automation. All behaviour
 * is driven by real events; nothing here polls the radio.
 */
@Singleton
class AccessoryCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessories: AccessoryManager,
    private val system: SystemBluetoothMonitor,
    private val settings: SettingsRepository,
    private val devices: DeviceRepository,
    private val battery: BatteryRepository,
    private val listening: ListeningRepository,
    private val gestures: GestureRepository,
    private val secrets: SecretStore,
    private val notifications: Notifications,
    private val media: MediaControl,
    private val location: LocationHelper,
    private val outputs: AudioOutputs,
    private val automation: AutomationRunner,
    private val audioProfiles: AudioProfileRepository,
    private val profileApplier: AudioProfileApplier,
    private val maintenance: DataMaintenance,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false
    private val lowAlerted = mutableSetOf<String>()
    private val chargedAlerted = mutableSetOf<String>()
    private val pausedByEarRemoval = mutableSetOf<String>()
    private val lastInEar = mutableMapOf<String, Int>()
    private val sessionIds = mutableMapOf<String, Long>()
    private val highVolumeSeconds = mutableMapOf<String, Long>()
    @Volatile private var volumeFraction = 0f

    fun start() {
        if (started) return
        started = true
        notifications.createChannels()
        accessories.start()

        scope.launch {
            maintenance.applyRetention()
            audioProfiles.ensureBuiltIns()
            profileApplier.restore()
            listening.closeDangling(clock.nowMillis())
        }

        // User settings and remembered data -> accessory engine configuration.
        scope.launch {
            kotlinx.coroutines.flow.combine(settings.settings, devices.knownDevices) { s, known -> s to known }.collect { (s, known) ->
                accessories.updateConfig { cfg ->
                    cfg.copy(
                        channelEnabled = s.accessoryChannelEnabled,
                        experimentalEnabled = s.experimentalEnabled,
                        rememberedModels = known.mapNotNull { k -> k.model?.let { m -> runCatching { AccessoryModel.valueOf(m) }.getOrNull()?.let { k.address to it } } }.toMap(),
                        observedFeatures = known.associate { it.address to DeviceRepository.observedFeatures(it) },
                        keys = known.associate { it.address to (secrets.get(SecretStore.irkName(it.address)) to secrets.get(SecretStore.encKeyName(it.address))) }
                            .filterValues { it.first != null || it.second != null },
                    )
                }
            }
        }

        scope.launch { system.events.collect { onSystemEvent(it) } }
        scope.launch { accessories.accessories.collect { list -> list.forEach { onState(it) }; updateServiceAndNotification(list) } }
        scope.launch { accessories.events.collect { (address, event) -> onAccessoryEvent(address, event) } }
        scope.launch {
            accessories.keysLearned.collect { (address, keys) ->
                keys.irk?.let { secrets.put(SecretStore.irkName(address), it) }
                keys.encryptionKey?.let { secrets.put(SecretStore.encKeyName(address), it) }
                Diag.i(TAG) { "Stored proximity keys for $address (encrypted)" }
            }
        }
        scope.launch { outputs.mediaVolumeFraction().collect { volumeFraction = it } }
        automation.start()
    }

    private suspend fun onSystemEvent(e: SystemBluetoothEvent) {
        when (e) {
            is SystemBluetoothEvent.Connected -> {
                devices.recordSeen(e.accessory.address, e.accessory.name, AccessoryModel.guessFromName(e.accessory.name))
                devices.recordEvent(e.accessory.address, e.accessory.name, ConnectionEventType.CONNECTED)
                lowAlerted.remove(e.accessory.address); chargedAlerted.remove(e.accessory.address)
                startMonitorService()
            }
            is SystemBluetoothEvent.Disconnected -> {
                val s = settings.current()
                val wasWorn = (lastInEar[e.address] ?: 0) > 0
                val type = if (wasWorn) ConnectionEventType.UNEXPECTED_DISCONNECT else ConnectionEventType.DISCONNECTED
                devices.recordEvent(e.address, e.name, type, if (wasWorn) "Disconnected while at least one bud was in ear" else null)
                endListeningSession(e.address)
                lastInEar.remove(e.address)
                if (wasWorn && s.disconnectAlertsEnabled) {
                    notifications.alert(Notifications.CH_SECURITY, Notifications.ID_DISCONNECT, "${e.name ?: "Accessory"} disconnected",
                        "It disconnected while in use. If this was not you, check where your accessory is.")
                }
                if (s.locationOnDisconnectEnabled) {
                    location.currentLocation()?.let { devices.recordLocation(e.address, it.latitude, it.longitude, it.accuracy, it.time) }
                }
            }
            is SystemBluetoothEvent.AdapterChanged -> Unit
        }
    }

    private suspend fun onState(state: AccessoryState) {
        val readings = state.battery.values.toList()
        if (readings.isNotEmpty()) battery.record(state.address, readings)
        if (state.aap?.observedFeatures?.isNotEmpty() == true) devices.addObservedFeatures(state.address, state.aap!!.observedFeatures)
        state.aap?.deviceInfo?.let { info ->
            devices.recordDeviceInfo(state.address, info.modelNumber, info.firmwareVersion, AccessoryModel.fromModelNumber(info.modelNumber))
        }
        checkBatteryAlerts(state)
        trackListening(state)
    }

    private suspend fun checkBatteryAlerts(state: AccessoryState) {
        val s = settings.current()
        val buds = state.battery.filterKeys { it != BatteryComponent.CASE }.values
        if (buds.isEmpty()) return
        val lowest = buds.minBy { it.level }
        if (s.batteryAlertsEnabled && lowest.level <= s.lowBatteryThreshold && lowest.charging != ChargingState.CHARGING && lowAlerted.add(state.address)) {
            notifications.alert(Notifications.CH_BATTERY, Notifications.ID_LOW_BATTERY, "${state.name ?: "Accessory"} battery low",
                "${lowest.component.name.lowercase().replaceFirstChar { it.uppercase() }}: ${lowest.level}% (measured, ±${lowest.source.precisionPercent}%)")
        }
        if (lowest.level > s.lowBatteryThreshold + 5) lowAlerted.remove(state.address)
        val charged = buds.all { it.charging == ChargingState.CHARGING && it.level >= 100 }
        if (s.chargingAlertsEnabled && charged && chargedAlerted.add(state.address)) {
            notifications.alert(Notifications.CH_BATTERY, Notifications.ID_CHARGED, "${state.name ?: "Accessory"} fully charged", "Both earbuds report 100%.")
        }
        if (!charged) chargedAlerted.remove(state.address)
    }

    /** Ear detection -> auto pause/resume and listening-session analytics. */
    private suspend fun trackListening(state: AccessoryState) {
        val inEar = state.aap?.budsInEar ?: state.ble?.sighting?.status?.let { (if (it.leftInEar) 1 else 0) + (if (it.rightInEar) 1 else 0) }
        val headphones = state.model.isAirPodsMax
        val worn = when {
            inEar != null -> inEar
            headphones -> 1 // headphones without ear detection data: connected counts as listening time
            else -> return
        }
        val previous = lastInEar.put(state.address, worn)
        if (previous == worn) { accumulateVolume(state.address); return }
        val s = settings.current()
        if (previous != null && worn < previous && s.autoPauseOnEarRemoval && state.aap?.budsInEar != null && media.isMusicActive) {
            media.pause(); pausedByEarRemoval += state.address
        } else if (previous != null && worn > previous && s.autoResumeOnEarInsert && pausedByEarRemoval.remove(state.address)) {
            media.play()
        }
        if (worn > 0 && sessionIds[state.address] == null) {
            sessionIds[state.address] = listening.start(state.address, clock.nowMillis())
            highVolumeSeconds[state.address] = 0
        } else if (worn == 0) endListeningSession(state.address)
    }

    private var lastVolumeTick = 0L
    private fun accumulateVolume(address: String) {
        val now = clock.nowMillis()
        if (sessionIds[address] != null && volumeFraction >= 0.8f && lastVolumeTick > 0) {
            highVolumeSeconds[address] = (highVolumeSeconds[address] ?: 0) + ((now - lastVolumeTick) / 1000).coerceAtMost(60)
        }
        lastVolumeTick = now
    }

    private suspend fun endListeningSession(address: String) {
        sessionIds.remove(address)?.let { listening.stop(it, clock.nowMillis(), highVolumeSeconds.remove(address) ?: 0) }
    }

    private suspend fun onAccessoryEvent(address: String, event: AapEvent) {
        when (event) {
            is AapEvent.StemPress -> handleGesture(address, event)
            is AapEvent.ConversationActivity -> Unit // the accessory itself lowers volume/switches mode
            else -> Unit
        }
    }

    private suspend fun handleGesture(address: String, press: AapEvent.StemPress) {
        val binding = gestures.all().firstOrNull { b ->
            b.pressType == press.type.name && (b.bud == null || b.bud == press.bud.name)
        } ?: return
        Diag.i(TAG) { "Gesture ${press.type} on ${press.bud} -> ${binding.action}" }
        when (runCatching { GestureAction.valueOf(binding.action) }.getOrNull()) {
            GestureAction.PLAY_PAUSE -> media.playPause()
            GestureAction.NEXT_TRACK -> media.next()
            GestureAction.PREVIOUS_TRACK -> media.previous()
            GestureAction.CYCLE_LISTENING_MODE -> {
                val state = accessories.accessories.value.firstOrNull { it.address == address } ?: return
                val modes = state.aap?.cycleModes?.takeIf { it.size >= 2 }?.toList() ?: state.model.listeningModes.toList()
                if (modes.isEmpty()) return
                val current = state.aap?.listeningMode
                val next = modes[(modes.indexOf(current) + 1).mod(modes.size)]
                accessories.setListeningMode(address, next)
            }
            GestureAction.APPLY_AUDIO_PROFILE -> binding.argument?.toLongOrNull()?.let { profileApplier.apply(it) }
            GestureAction.TOGGLE_PROFILE -> binding.argument?.let { p ->
                settings.update { s -> s.copy(activeProfiles = if (p in s.activeProfiles) s.activeProfiles - p else s.activeProfiles + p) }
            }
            GestureAction.LAUNCH_APP -> binding.argument?.let { pkg ->
                // Android 10+ blocks background activity starts; this works only while Auralynx is visible.
                context.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            GestureAction.START_RECORDING -> RecorderService.start(context)
            null -> Unit
        }
    }

    private fun updateServiceAndNotification(list: List<AccessoryState>) {
        if (list.isEmpty()) MonitorService.stop(context) else notifications.updateMonitor(list.first())
    }

    private fun startMonitorService() {
        if (Build.VERSION.SDK_INT >= 31 && !app.auralynx.core.bluetooth.system.BluetoothPermissions.hasConnect(context)) return
        MonitorService.start(context)
    }

    companion object { private const val TAG = "Coordinator" }
}
