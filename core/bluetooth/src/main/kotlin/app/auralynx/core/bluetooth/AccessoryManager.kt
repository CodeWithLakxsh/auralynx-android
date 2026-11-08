package app.auralynx.core.bluetooth

import app.auralynx.core.bluetooth.aap.AapSession
import app.auralynx.core.bluetooth.aap.AapTransport
import app.auralynx.core.bluetooth.aap.ChannelError
import app.auralynx.core.bluetooth.aap.PacketRecord
import app.auralynx.core.bluetooth.ble.BleSighting
import app.auralynx.core.bluetooth.ble.ProximityScanner
import app.auralynx.core.bluetooth.ble.ScanIntensity
import app.auralynx.core.bluetooth.system.ConnectedAccessory
import app.auralynx.core.bluetooth.system.SystemBluetoothMonitor
import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.common.Clock
import app.auralynx.core.common.Diag
import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.Outcome
import app.auralynx.core.protocol.aap.AapEvent
import app.auralynx.core.protocol.ble.ProximityCrypto
import app.auralynx.core.protocol.devices.AccessoryModel
import app.auralynx.core.protocol.devices.CapabilityRegistry
import app.auralynx.core.protocol.devices.ChannelUnavailableReason
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.devices.TransportState
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.PressType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Runtime configuration pushed from user settings. */
data class AccessoryConfig(
    val channelEnabled: Boolean = true,
    val experimentalEnabled: Boolean = false,
    /** Models remembered from earlier sessions (address -> model). */
    val rememberedModels: Map<String, AccessoryModel> = emptyMap(),
    /** Features previously observed on each accessory. */
    val observedFeatures: Map<String, Set<Feature>> = emptyMap(),
    /** Proximity keys previously obtained from each accessory. */
    val keys: Map<String, Pair<ByteArray?, ByteArray?>> = emptyMap(),
    /** Whether background BLE monitoring may run (needs scan permission; off when nothing is connected). */
    val bleMonitoring: Boolean = true,
    /** Faster BLE scanning (BALANCED) while media plays, so advertisement-based ear detection is timely. */
    val mediaActive: Boolean = false,
)

/** An event tied to the accessory that produced it. */
data class AccessoryEvent(val address: String, val event: AapEvent)

/**
 * Single source of truth for connected accessories. UI and services only read [accessories] and
 * call the command functions; nothing else talks to Bluetooth directly.
 */
@Singleton
class AccessoryManager @Inject constructor(
    private val system: SystemBluetoothMonitor,
    private val transport: AapTransport,
    private val scanner: ProximityScanner,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val config = MutableStateFlow(AccessoryConfig())
    private val sessions = ConcurrentHashMap<String, AapSession>()
    private val connectJobs = ConcurrentHashMap<String, Job>()
    private val channelStatus = MutableStateFlow<Map<String, ChannelStatus>>(emptyMap())
    private val sessionStates = MutableStateFlow<Map<String, app.auralynx.core.bluetooth.aap.AapState>>(emptyMap())
    private val bleSightings = MutableStateFlow<Map<String, BleSnapshot>>(emptyMap())

    private val _events = MutableSharedFlow<AccessoryEvent>(extraBufferCapacity = 64)
    /** Transient accessory events (stem presses, speech activity, head tracking, keys). */
    val events = _events.asSharedFlow()

    private val _keysLearned = MutableSharedFlow<Pair<String, AapEvent.ProximityKeys>>(extraBufferCapacity = 4)
    val keysLearned = _keysLearned.asSharedFlow()

    val adapterEnabled: StateFlow<Boolean> get() = system.adapterEnabled
    val hasBluetooth: Boolean get() = system.hasAdapter

    val accessories: StateFlow<List<AccessoryState>> = combine(
        system.connected, channelStatus, sessionStates, bleSightings, config,
    ) { connected, channels, aap, ble, cfg ->
        connected.values.sortedBy { it.connectedAtMillis }.map { c -> build(c, channels[c.address], aap[c.address], ble[c.address], cfg) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var started = false

    fun start() {
        system.start()
        if (started) return
        started = true
        scope.launch {
            system.connected.collect { connected ->
                connected.values.forEach { ensureChannel(it.address) }
                (sessions.keys + connectJobs.keys - connected.keys).forEach { teardown(it) }
            }
        }
        scope.launch {
            // BLE monitoring runs only while at least one accessory is connected and monitoring is allowed.
            combine(system.connected.map { it.isNotEmpty() }, config) { any, cfg ->
                when {
                    !any || !cfg.bleMonitoring -> null
                    cfg.mediaActive -> ScanIntensity.BALANCED
                    else -> ScanIntensity.LOW_POWER
                }
            }.distinctUntilChanged()
                .flatMapLatest { mode -> if (mode != null) scanner.proximity(mode) else emptyFlow() }
                .collect { onSighting(it) }
        }
        scope.launch {
            config.map { it.channelEnabled }.collect { enabled ->
                if (enabled) system.connected.value.keys.forEach { ensureChannel(it) }
                else (sessions.keys + connectJobs.keys).toList().forEach { teardown(it, status = ChannelStatus.DisabledByUser) }
            }
        }
    }

    fun updateConfig(transform: (AccessoryConfig) -> AccessoryConfig) = config.update(transform)

    /** User-initiated reconnect (e.g. "Retry" button). Resets the backoff. */
    fun retryChannel(address: String) {
        teardown(address)
        ensureChannel(address)
    }

    fun packetLog(address: String): StateFlow<List<PacketRecord>> =
        sessions[address]?.packetLog ?: MutableStateFlow(emptyList())

    // ---- Commands --------------------------------------------------------------------------

    suspend fun setListeningMode(address: String, mode: ListeningMode): Outcome<Unit> = withSession(address) { it.setListeningMode(mode) }
    suspend fun setConversationAwareness(address: String, enabled: Boolean) = withSession(address) { it.setConversationAwareness(enabled) }
    suspend fun setAdaptiveNoiseLevel(address: String, level: Int) = withSession(address) { it.setAdaptiveNoiseLevel(level) }
    suspend fun setListeningModeCycle(address: String, modes: Set<ListeningMode>) = withSession(address) { it.setListeningModeCycle(modes) }
    suspend fun rename(address: String, name: String) = withSession(address) { it.rename(name) }
    suspend fun claimStemPresses(address: String, types: Set<PressType>) = withSession(address) { it.claimStemPresses(types) }
    suspend fun setHeadTracking(address: String, enabled: Boolean) = withSession(address) { it.setHeadTracking(enabled) }

    private suspend fun withSession(address: String, block: suspend (AapSession) -> Outcome<Unit>): Outcome<Unit> {
        val session = sessions[address]?.takeIf { it.isOpen.value } ?: return Outcome.Failure(FailureReason.NOT_CONNECTED)
        return block(session)
    }

    // ---- Channel lifecycle -----------------------------------------------------------------

    private fun ensureChannel(address: String) {
        if (sessions[address]?.isOpen?.value == true || connectJobs[address]?.isActive == true) return
        if (!config.value.channelEnabled) {
            setStatus(address, ChannelStatus.DisabledByUser); return
        }
        if (transport.osSupport() == AapTransport.OsSupport.UNSUPPORTED) {
            setStatus(address, ChannelStatus.OsUnsupported("Android does not yet let apps open this accessory channel with public APIs"))
            return
        }
        connectJobs[address] = scope.launch {
            var attempt = 0
            while (attempt < RETRY_BACKOFF_MILLIS.size) {
                val backoff = RETRY_BACKOFF_MILLIS[attempt]
                if (backoff > 0) {
                    setStatus(address, ChannelStatus.Failed("Retrying", clock.nowMillis() + backoff))
                    delay(backoff)
                }
                if (address !in system.connected.value) return@launch
                setStatus(address, ChannelStatus.Connecting(attempt + 1))
                try {
                    val connection = transport.connect(address)
                    val session = AapSession(connection, scope, clock)
                    sessions[address] = session
                    session.start()
                    setStatus(address, ChannelStatus.Connected)
                    observe(address, session)
                    // Suspend until the session ends.
                    session.isOpen.first { !it }
                    sessions.remove(address)
                    sessionStates.update { it - address }
                    if (address !in system.connected.value) return@launch
                    Diag.w(TAG) { "Channel to $address dropped; reconnecting" }
                    // A session that worked earns a fresh retry budget, starting after a short pause.
                    attempt = 1
                    continue
                } catch (e: ChannelError.OsUnsupported) {
                    setStatus(address, ChannelStatus.OsUnsupported(e.message ?: "Unsupported")); return@launch
                } catch (e: ChannelError.PermissionMissing) {
                    setStatus(address, ChannelStatus.Failed("Nearby devices permission not granted", null)); return@launch
                } catch (e: ChannelError) {
                    Diag.w(TAG, e) { "Channel attempt ${attempt + 1} to $address failed" }
                }
                attempt++
            }
            setStatus(address, ChannelStatus.Failed("The accessory did not accept the connection. Your phone's Bluetooth stack may not support it yet.", null))
        }.also { job -> job.invokeOnCompletion { connectJobs.remove(address, job) } }
    }

    private fun observe(address: String, session: AapSession) {
        scope.launch {
            session.state.collect { s -> sessionStates.update { it + (address to s) } }
        }
        scope.launch {
            session.events.collect { e ->
                _events.emit(AccessoryEvent(address, e))
                if (e is AapEvent.ProximityKeys) {
                    config.update { it.copy(keys = it.keys + (address to (e.irk to e.encryptionKey))) }
                    _keysLearned.emit(address to e)
                }
            }
        }
    }

    private fun teardown(address: String, status: ChannelStatus? = null) {
        connectJobs.remove(address)?.cancel()
        sessions.remove(address)?.close()
        sessionStates.update { it - address }
        bleSightings.update { it - address }
        earDebouncers.remove(address)
        trustedAdvertisers.values.removeAll { it == address }
        channelStatus.update { if (status == null) it - address else it + (address to status) }
    }

    private fun setStatus(address: String, status: ChannelStatus) = channelStatus.update { it + (address to status) }

    // ---- BLE matching ----------------------------------------------------------------------

    private fun onSighting(s: BleSighting) {
        val connected = system.connected.value.values
        val cfg = config.value
        // 1) cryptographic match via IRK
        val keyed = connected.firstOrNull { c ->
            cfg.keys[c.address]?.first?.let { irk -> ProximityCrypto.resolvesTo(s.address, irk) } == true
        }
        if (keyed != null) {
            val encKey = cfg.keys[keyed.address]?.second
            bleSightings.update { it + (keyed.address to BleSnapshot(s, BleMatch.KEY_VERIFIED, encKey, debounceEar(keyed.address, s))) }
            return
        }
        // 2) heuristic: the single connected accessory compatible with this advertisement, strong signal.
        //    With strong model evidence (device information / remembered) the exact model must match;
        //    with only a Bluetooth-name guess the product family must match, because the name cannot
        //    distinguish generations (e.g. "AirPods Pro" vs "AirPods Pro 2").
        val sightingModel = s.model
        val candidates = if (sightingModel == null) emptyList() else connected.filter { c ->
            if (cfg.keys[c.address]?.first != null) return@filter false
            val (model, evidence) = resolveModel(c, null, null, cfg)
            when (evidence) {
                ModelEvidence.DEVICE_INFORMATION, ModelEvidence.REMEMBERED -> model == sightingModel
                else -> model.family != AccessoryModel.Family.UNKNOWN && model.family == sightingModel.family
            }
        }
        // A resolvable private address stays constant until it rotates (~15 min). Once an address was
        // matched with a strong signal, later frames from it are the same physical device even when
        // the signal dips (head shadowing, the other bud transmitting). Verified on OnePlus Pad 3,
        // where genuine frames at -67..-83 dBm were otherwise dropped.
        val trustedFor = trustedAdvertisers[s.address]
        val matched = candidates.size == 1 &&
            (s.rssi >= PROBABLE_MIN_RSSI || trustedFor == candidates.single().address)
        if (matched && s.rssi >= PROBABLE_MIN_RSSI) trustedAdvertisers[s.address] = candidates.single().address
        if (matched) {
            val address = candidates.single().address
            bleSightings.update { it + (address to BleSnapshot(s, BleMatch.PROBABLE, stableBudsInEar = debounceEar(address, s))) }
        }
        logSighting(s, if (matched) "matched (probable)" else "unmatched: candidates=${candidates.size}, rssi=${s.rssi}")
    }

    private val earDebouncers = ConcurrentHashMap<String, EarStateDebouncer>()

    /** Advertising address -> connected accessory it was matched to with a strong signal. */
    private val trustedAdvertisers = ConcurrentHashMap<String, String>()

    /** Diagnostic switch: log every advertisement frame instead of only changes. */
    @Volatile var traceAllSightings: Boolean = false

    private fun debounceEar(address: String, s: BleSighting): Int? {
        val d = earDebouncers.getOrPut(address) { EarStateDebouncer() }
        val count = (if (s.status.leftInEar) 1 else 0) + (if (s.status.rightInEar) 1 else 0)
        if (d.offer(count, s.timeMillis)) Diag.d(TAG) { "BLE ear state for $address is now $count bud(s) in ear" }
        return d.stable
    }

    private val lastSightingLog = ConcurrentHashMap<String, Pair<Long, String>>()

    /** One diagnostic line per advertising address every 30 s, or immediately when the status changes. */
    private fun logSighting(s: BleSighting, result: String) {
        val now = clock.nowMillis()
        val st = s.status
        val signature = "L=${st.leftBattery}${if (st.leftCharging) "+" else ""} R=${st.rightBattery}${if (st.rightCharging) "+" else ""} " +
            "case=${st.caseBattery} inEar(L=${st.leftInEar},R=${st.rightInEar}) lid=${st.lid} status=0x%02X".format(st.rawStatus)
        val last = lastSightingLog[s.address]
        if (!traceAllSightings && last != null && last.second == signature && now - last.first < 30_000) return
        lastSightingLog[s.address] = now to signature
        Diag.d(TAG) {
            "BLE sighting addr=..%s model=0x%04X (%s) rssi=%d %s raw=[%s] -> %s".format(
                s.address.takeLast(5), st.modelCode, s.model?.name ?: "unknown", s.rssi, signature,
                s.publicBytes.joinToString(" ") { "%02X".format(it) }, result,
            )
        }
    }

    // ---- Composition -----------------------------------------------------------------------

    private fun build(
        c: ConnectedAccessory,
        channel: ChannelStatus?,
        aap: app.auralynx.core.bluetooth.aap.AapState?,
        ble: BleSnapshot?,
        cfg: AccessoryConfig,
    ): AccessoryState {
        val (model, evidence) = resolveModel(c, aap, ble, cfg)
        val status = channel ?: ChannelStatus.Idle
        val unavailable = when (status) {
            is ChannelStatus.OsUnsupported -> ChannelUnavailableReason.OS_UNSUPPORTED
            ChannelStatus.DisabledByUser -> ChannelUnavailableReason.DISABLED_BY_USER
            ChannelStatus.Connected -> null
            else -> ChannelUnavailableReason.NOT_CONNECTED
        }
        val transportState = TransportState(
            standardConnected = true,
            standardBatteryReported = c.hfpBatteryPercent != null,
            bleObserved = ble != null && clock.nowMillis() - ble.sighting.timeMillis < BLE_FRESH_MILLIS,
            channelConnected = status == ChannelStatus.Connected,
            channelUnavailableReason = unavailable,
            experimentalEnabled = cfg.experimentalEnabled,
        )
        val observed = cfg.observedFeatures[c.address].orEmpty() + aap?.observedFeatures.orEmpty()
        return AccessoryState(
            address = c.address, name = c.name, model = model, modelEvidence = evidence, profiles = c.profiles,
            connectedAtMillis = c.connectedAtMillis, channel = status, aap = aap, ble = ble,
            hfpBatteryPercent = c.hfpBatteryPercent,
            capabilities = CapabilityRegistry.resolve(model, transportState, observed),
        )
    }

    private fun resolveModel(
        c: ConnectedAccessory,
        aap: app.auralynx.core.bluetooth.aap.AapState?,
        ble: BleSnapshot?,
        cfg: AccessoryConfig,
    ): Pair<AccessoryModel, ModelEvidence> {
        aap?.deviceInfo?.modelNumber?.let { AccessoryModel.fromModelNumber(it) }?.let { return it to ModelEvidence.DEVICE_INFORMATION }
        ble?.sighting?.model?.takeIf { ble.match == BleMatch.KEY_VERIFIED }?.let { return it to ModelEvidence.BLE_ADVERTISEMENT }
        cfg.rememberedModels[c.address]?.let { return it to ModelEvidence.REMEMBERED }
        ble?.sighting?.model?.let { return it to ModelEvidence.BLE_ADVERTISEMENT }
        AccessoryModel.guessFromName(c.name)?.let { return it to ModelEvidence.BLUETOOTH_NAME }
        return AccessoryModel.UNKNOWN to ModelEvidence.UNKNOWN
    }

    companion object {
        private const val TAG = "AccessoryManager"
        /** Initial attempt plus five retries; then stop until the next standard connection. */
        val RETRY_BACKOFF_MILLIS = listOf(0L, 2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
        const val PROBABLE_MIN_RSSI = -65
        const val BLE_FRESH_MILLIS = 90_000L
    }
}
