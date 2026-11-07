package app.auralynx.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auralynx.automation.AutomationRunner
import app.auralynx.core.automation.ActionTarget
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.core.bluetooth.AccessoryState
import app.auralynx.core.bluetooth.ble.BleSighting
import app.auralynx.core.bluetooth.ble.ProximityScanner
import app.auralynx.core.bluetooth.ble.ScanIntensity
import app.auralynx.core.bluetooth.system.BondedAccessory
import app.auralynx.core.bluetooth.system.SystemBluetoothMonitor
import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.Outcome
import app.auralynx.core.data.db.GestureBindingEntity
import app.auralynx.core.data.db.KnownDeviceEntity
import app.auralynx.core.data.repo.DeviceRepository
import app.auralynx.core.data.repo.GestureRepository
import app.auralynx.core.data.repo.ListeningRepository
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.PressType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val accessories: AccessoryManager,
    private val system: SystemBluetoothMonitor,
    private val scanner: ProximityScanner,
    private val devices: DeviceRepository,
    private val gestures: GestureRepository,
    private val listening: ListeningRepository,
    private val settings: SettingsRepository,
    private val runner: AutomationRunner,
) : ViewModel() {

    val adapterEnabled = accessories.adapterEnabled
    val hasBluetooth = accessories.hasBluetooth

    /** The accessory the UI shows: the user's selection if connected, else the first connected one. */
    val active: StateFlow<AccessoryState?> = combine(accessories.accessories, settings.settings) { list, s ->
        list.firstOrNull { it.address == s.selectedDeviceAddress } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val all: StateFlow<List<AccessoryState>> = accessories.accessories
    val known: StateFlow<List<KnownDeviceEntity>> = devices.knownDevices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val bindings: StateFlow<List<GestureBindingEntity>> = gestures.bindings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val listeningSessions = active.flatMapLatest { a ->
        if (a == null) flowOf(emptyList()) else listening.sessions(a.address, System.currentTimeMillis() - 7 * 86_400_000L)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    private val _bonded = MutableStateFlow<List<BondedAccessory>>(emptyList())
    val bonded: StateFlow<List<BondedAccessory>> = _bonded.asStateFlow()
    fun refreshBonded() { system.start(); accessories.start(); _bonded.value = system.bondedAccessories() }

    /** Nearby proximity advertisements while the discovery screen is visible. */
    val nearby: StateFlow<Map<String, BleSighting>> = scanner.proximity(ScanIntensity.LOW_LATENCY)
        .runningFold(emptyMap<String, BleSighting>()) { acc, s -> (acc + (s.address to s)).filterValues { System.currentTimeMillis() - it.timeMillis < 30_000 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), emptyMap())

    fun select(address: String) = viewModelScope.launch { settings.update { it.copy(selectedDeviceAddress = address) } }

    fun retryChannel() { active.value?.let { accessories.retryChannel(it.address) } }

    fun setMode(mode: ListeningMode) = command("Listening mode") { a ->
        accessories.setListeningMode(a, mode).also { if (it is Outcome.Success) runner.noteManualChange(ActionTarget.LISTENING_MODE) }
    }

    fun setConversationAwareness(on: Boolean) = command("Conversation awareness") { a ->
        accessories.setConversationAwareness(a, on).also { if (it is Outcome.Success) runner.noteManualChange(ActionTarget.CONVERSATION_AWARENESS) }
    }

    fun setAdaptiveLevel(level: Int) = command("Adaptive level") { accessories.setAdaptiveNoiseLevel(it, level) }
    fun setCycle(modes: Set<ListeningMode>) = command("Long-press modes") { accessories.setListeningModeCycle(it, modes) }
    fun rename(name: String) = command("Rename") { a ->
        accessories.rename(a, name).also {
            if (it is Outcome.Success) _message.value = "Name sent. Android may show the old name until you re-pair the accessory."
        }
    }

    fun saveBinding(binding: GestureBindingEntity) = viewModelScope.launch {
        gestures.save(binding)
        claimBoundPresses()
    }

    fun deleteBinding(id: Long) = viewModelScope.launch { gestures.delete(id); claimBoundPresses() }

    /** Tells the accessory to report only the press types that have a binding. */
    private suspend fun claimBoundPresses() {
        val a = active.value ?: return
        val types = gestures.all().mapNotNull { runCatching { PressType.valueOf(it.pressType) }.getOrNull() }.toSet()
        accessories.claimStemPresses(a.address, types)
    }

    private fun command(what: String, block: suspend (String) -> Outcome<Unit>) = viewModelScope.launch {
        val a = active.value ?: run { _message.value = "No accessory connected"; return@launch }
        when (val r = block(a.address)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> _message.value = "$what: " + when (r.reason) {
                FailureReason.NOT_CONNECTED -> "the accessory channel is not connected"
                FailureReason.TIMEOUT -> "the accessory did not confirm the change"
                FailureReason.UNSUPPORTED_BY_ACCESSORY -> "your accessory did not accept this setting"
                else -> "failed (${r.reason.name.lowercase()})"
            }
        }
    }
}
