package app.auralynx.core.bluetooth.aap

import app.auralynx.core.common.Clock
import app.auralynx.core.common.Diag
import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.Outcome
import app.auralynx.core.common.toHex
import app.auralynx.core.common.u16le
import app.auralynx.core.protocol.aap.AapCommands
import app.auralynx.core.protocol.aap.AapEvent
import app.auralynx.core.protocol.aap.AapOpcode
import app.auralynx.core.protocol.aap.AapParser
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.BatteryReading
import app.auralynx.core.protocol.model.BudPlacement
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.PressType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Live state learned from the accessory. `null` fields have not been reported yet. */
data class AapState(
    val battery: Map<BatteryComponent, BatteryReading> = emptyMap(),
    val primaryBud: BudPlacement? = null,
    val secondaryBud: BudPlacement? = null,
    val listeningMode: ListeningMode? = null,
    val cycleModes: Set<ListeningMode>? = null,
    val conversationAwareness: Boolean? = null,
    val adaptiveNoiseLevel: Int? = null,
    val deviceInfo: AapEvent.DeviceInfo? = null,
    val wearerSpeaking: Boolean = false,
    val observedFeatures: Set<Feature> = emptySet(),
    val packetsReceived: Int = 0,
) {
    val budsInEar: Int?
        get() = if (primaryBud == null) null else listOf(primaryBud, secondaryBud).count { it == BudPlacement.IN_EAR }
}

/** A packet as seen on the wire, for the research/diagnostics packet log. */
data class PacketRecord(val timeMillis: Long, val outgoing: Boolean, val opcode: Int?, val hex: String)

/**
 * One AAP conversation with one accessory.
 *
 * Lifecycle: [start] sends the handshake, host-feature announcement and notification
 * subscription, then requests proximity keys. Commands are confirmed by the accessory echoing the
 * new value; a command is reported as successful only when that echo arrives.
 */
class AapSession(
    private val connection: AapConnection,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val confirmTimeoutMillis: Long = 2_500,
) {
    private val _state = MutableStateFlow(AapState())
    val state: StateFlow<AapState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AapEvent>(extraBufferCapacity = 64)
    /** Every decoded packet, including transient ones (stem presses, speech activity, head tracking). */
    val events: SharedFlow<AapEvent> = _events.asSharedFlow()

    private val _closed = MutableStateFlow<Throwable?>(null)
    private val _isOpen = MutableStateFlow(true)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()
    /** The error that ended the session, if any. */
    val closedCause: StateFlow<Throwable?> = _closed.asStateFlow()

    private val _packetLog = MutableStateFlow<List<PacketRecord>>(emptyList())
    val packetLog: StateFlow<List<PacketRecord>> = _packetLog.asStateFlow()

    private var reader: Job? = null

    suspend fun start() {
        reader = scope.launch {
            try {
                connection.incoming.collect { handle(it) }
                finish(null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                finish(e)
            }
        }
        send(AapCommands.handshake())
        send(AapCommands.hostFeatures())
        send(AapCommands.requestNotifications())
        send(AapCommands.requestProximityKeys())
    }

    fun close() {
        connection.close()
        reader?.cancel()
        finish(null)
    }

    suspend fun setListeningMode(mode: ListeningMode): Outcome<Unit> =
        sendAndConfirm(AapCommands.setListeningMode(mode)) { it is AapEvent.ListeningModeChanged }
            .let { result ->
                when {
                    result == null -> Outcome.Failure(FailureReason.TIMEOUT)
                    (result as AapEvent.ListeningModeChanged).mode == mode -> Outcome.Success(Unit)
                    // The accessory answered with a different mode: the requested one is not available.
                    else -> Outcome.Failure(FailureReason.UNSUPPORTED_BY_ACCESSORY)
                }
            }

    suspend fun setConversationAwareness(enabled: Boolean): Outcome<Unit> =
        confirmEquals(AapCommands.setConversationAwareness(enabled), AapEvent.ConversationAwarenessSetting(enabled))

    suspend fun setAdaptiveNoiseLevel(level: Int): Outcome<Unit> =
        confirmEquals(AapCommands.setAdaptiveNoiseLevel(level), AapEvent.AdaptiveNoiseLevel(level.coerceIn(0, 100)))

    suspend fun setListeningModeCycle(modes: Set<ListeningMode>): Outcome<Unit> =
        confirmEquals(AapCommands.setListeningModeCycle(modes), AapEvent.ListeningModeCycleChanged(modes))

    /** Renames the accessory. The firmware does not echo renames, so success means "sent". */
    suspend fun rename(name: String): Outcome<Unit> = runCatching { send(AapCommands.rename(name)) }
        .fold({ Outcome.Success(Unit) }, { Outcome.Failure(FailureReason.IO_ERROR, it) })

    suspend fun claimStemPresses(types: Set<PressType>): Outcome<Unit> = runCatching { send(AapCommands.claimStemPresses(types)) }
        .fold({ Outcome.Success(Unit) }, { Outcome.Failure(FailureReason.IO_ERROR, it) })

    suspend fun setHeadTracking(enabled: Boolean): Outcome<Unit> =
        runCatching { send(if (enabled) AapCommands.startHeadTracking() else AapCommands.stopHeadTracking()) }
            .fold({ Outcome.Success(Unit) }, { Outcome.Failure(FailureReason.IO_ERROR, it) })

    private suspend fun confirmEquals(packet: ByteArray, expected: AapEvent): Outcome<Unit> =
        when (sendAndConfirm(packet) { it == expected }) {
            null -> Outcome.Failure(FailureReason.TIMEOUT)
            else -> Outcome.Success(Unit)
        }

    /** Subscribes before sending so a fast echo cannot be missed. */
    private suspend fun sendAndConfirm(packet: ByteArray, predicate: (AapEvent) -> Boolean): AapEvent? {
        if (!_isOpen.value) return null
        return coroutineScope {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(confirmTimeoutMillis) { _events.first(predicate) }
            }
            try {
                send(packet)
            } catch (e: Exception) {
                waiter.cancel()
                return@coroutineScope null
            }
            waiter.await()
        }
    }

    private suspend fun send(packet: ByteArray) {
        log(true, packet)
        connection.send(packet)
    }

    private suspend fun handle(packet: ByteArray) {
        log(false, packet)
        val event = AapParser.parse(packet) ?: return
        _state.update { s -> reduce(s, event) }
        _events.emit(event)
    }

    private fun finish(cause: Throwable?) {
        if (!_isOpen.compareAndSet(true, false)) return
        _closed.value = cause
        Diag.i(TAG) { "AAP session ended: ${cause?.message ?: "closed"}" }
    }

    private fun log(outgoing: Boolean, packet: ByteArray) {
        val opcode = if (packet.size >= 6 && packet[0] == 0x04.toByte()) packet.u16le(4) else null
        // Device information and key material contain serial numbers / secrets: never keep their bytes.
        val hex = if (opcode == AapOpcode.DEVICE_INFO || opcode == AapOpcode.PROXIMITY_KEYS_RESPONSE) {
            "<${packet.size} bytes redacted>"
        } else {
            packet.toHex()
        }
        _packetLog.update { (it + PacketRecord(clock.nowMillis(), outgoing, opcode, hex)).takeLast(PACKET_LOG_SIZE) }
    }

    companion object {
        private const val TAG = "AapSession"
        const val PACKET_LOG_SIZE = 200

        internal fun reduce(s: AapState, event: AapEvent): AapState {
            val base = s.copy(packetsReceived = s.packetsReceived + 1)
            return when (event) {
                is AapEvent.Battery -> base.copy(
                    battery = s.battery + event.readings.associateBy { it.component },
                    observedFeatures = s.observedFeatures + Feature.BATTERY,
                )
                is AapEvent.EarDetection -> base.copy(
                    primaryBud = event.primary, secondaryBud = event.secondary,
                    observedFeatures = s.observedFeatures + Feature.EAR_DETECTION,
                )
                is AapEvent.ListeningModeChanged -> base.copy(
                    listeningMode = event.mode,
                    observedFeatures = s.observedFeatures + Feature.NOISE_CONTROL +
                        (if (event.mode == ListeningMode.ADAPTIVE) setOf(Feature.ADAPTIVE_AUDIO) else emptySet()),
                )
                is AapEvent.ListeningModeCycleChanged -> base.copy(cycleModes = event.modes)
                is AapEvent.ConversationAwarenessSetting -> base.copy(
                    conversationAwareness = event.enabled,
                    observedFeatures = s.observedFeatures + Feature.CONVERSATION_AWARENESS,
                )
                is AapEvent.AdaptiveNoiseLevel -> base.copy(adaptiveNoiseLevel = event.level)
                is AapEvent.ConversationActivity -> base.copy(wearerSpeaking = event.speaking)
                is AapEvent.DeviceInfo -> base.copy(deviceInfo = event)
                is AapEvent.StemPress -> base.copy(observedFeatures = s.observedFeatures + Feature.STEM_PRESS_EVENTS)
                is AapEvent.HeadTracking -> base.copy(observedFeatures = s.observedFeatures + Feature.HEAD_TRACKING)
                is AapEvent.ProximityKeys, is AapEvent.ControlValue, is AapEvent.Unknown -> base
            }
        }
    }
}
