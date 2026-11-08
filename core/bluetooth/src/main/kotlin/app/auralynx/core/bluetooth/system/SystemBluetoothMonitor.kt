package app.auralynx.core.bluetooth.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.auralynx.core.common.Diag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioProfile { A2DP, HEADSET }

/** An Apple/Beats accessory currently connected through standard Bluetooth profiles. */
data class ConnectedAccessory(
    val address: String,
    val name: String?,
    val profiles: Set<AudioProfile>,
    /** HFP battery indicator as reported by Android (10 % steps), if the system reports it. */
    val hfpBatteryPercent: Int? = null,
    val connectedAtMillis: Long,
)

data class BondedAccessory(val address: String, val name: String?)

/** Standard-Bluetooth events. Unexpected-disconnect detection is done by the caller. */
sealed interface SystemBluetoothEvent {
    data class Connected(val accessory: ConnectedAccessory) : SystemBluetoothEvent
    data class Disconnected(val address: String, val name: String?) : SystemBluetoothEvent
    data class AdapterChanged(val enabled: Boolean) : SystemBluetoothEvent
}

/**
 * Observes the adapter and A2DP/HFP connections using public profile proxies and broadcasts.
 *
 * It does no polling: initial state comes from [BluetoothProfile.getConnectedDevices] and
 * everything afterwards from broadcasts. [start] is idempotent and safe to call before the
 * BLUETOOTH_CONNECT permission is granted (it then reports nothing until called again).
 */
@Singleton
class SystemBluetoothMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter

    private val _adapterEnabled = MutableStateFlow(adapter?.isEnabled == true)
    val adapterEnabled: StateFlow<Boolean> = _adapterEnabled.asStateFlow()

    private val _connected = MutableStateFlow<Map<String, ConnectedAccessory>>(emptyMap())
    val connected: StateFlow<Map<String, ConnectedAccessory>> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<SystemBluetoothEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SystemBluetoothEvent> = _events.asSharedFlow()

    val hasAdapter: Boolean get() = adapter != null

    private var registered = false
    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null

    @Synchronized
    fun start() {
        if (adapter == null || !BluetoothPermissions.hasConnect(context)) return
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(ACTION_BATTERY_LEVEL_CHANGED)
            }
            // These Bluetooth broadcasts are sent by the Bluetooth stack process (UID 1002), not the
            // system UID, so a NOT_EXPORTED receiver never receives them (verified on OxygenOS 16).
            // All registered actions are protected broadcasts that only the platform can send, so
            // exporting the receiver does not let other apps spoof connection events.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        if (a2dp == null) requestProxy(BluetoothProfile.A2DP)
        if (headset == null) requestProxy(BluetoothProfile.HEADSET)
        refreshFromProxies()
    }

    /**
     * Reconciles the connected set with what the profile proxies report now. Called when the app
     * resumes and when the adapter turns on, so connections made while a broadcast could not be
     * delivered (Bluetooth off, process not running) are still picked up. Reads cached proxy state
     * only; no radio activity.
     */
    @SuppressLint("MissingPermission")
    fun refreshFromProxies() {
        if (!BluetoothPermissions.hasConnect(context)) return
        _adapterEnabled.value = adapter?.isEnabled == true
        val a = a2dp; val h = headset
        val a2dpDevices = a?.let { runCatching { it.connectedDevices }.getOrNull() }.orEmpty().filter { isAppleAudioAccessory(it) }
        val hfpDevices = h?.let { runCatching { it.connectedDevices }.getOrNull() }.orEmpty().filter { isAppleAudioAccessory(it) }
        a2dpDevices.forEach { onProfileConnected(it, AudioProfile.A2DP) }
        hfpDevices.forEach { onProfileConnected(it, AudioProfile.HEADSET) }
        if (a != null && h != null) {
            val live = (a2dpDevices + hfpDevices).map { it.address }.toSet()
            _connected.value.values.filter { it.address !in live }.forEach { disconnect(it.address, it.name) }
        }
    }

    /** Bonded (paired) Apple/Beats accessories, for the discovery screen. */
    @SuppressLint("MissingPermission")
    fun bondedAccessories(): List<BondedAccessory> {
        if (adapter == null || !BluetoothPermissions.hasConnect(context)) return emptyList()
        return runCatching {
            adapter?.bondedDevices.orEmpty().filter { isAppleAudioAccessory(it) }.map { BondedAccessory(it.address, it.name) }
        }.getOrElse { emptyList() }
    }

    private fun requestProxy(profile: Int) {
        runCatching {
            adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                @SuppressLint("MissingPermission")
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    if (p == BluetoothProfile.A2DP) a2dp = proxy else headset = proxy
                    val audioProfile = if (p == BluetoothProfile.A2DP) AudioProfile.A2DP else AudioProfile.HEADSET
                    runCatching { proxy.connectedDevices }.getOrNull().orEmpty()
                        .filter { isAppleAudioAccessory(it) }
                        .forEach { onProfileConnected(it, audioProfile) }
                }

                override fun onServiceDisconnected(p: Int) {
                    if (p == BluetoothProfile.A2DP) a2dp = null else headset = null
                }
            }, profile)
        }.onFailure { Diag.w(TAG, it) { "Profile proxy $profile unavailable" } }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF) == BluetoothAdapter.STATE_ON
                    _adapterEnabled.value = on
                    _events.tryEmit(SystemBluetoothEvent.AdapterChanged(on))
                    if (!on) _connected.value.values.forEach { disconnect(it.address, it.name) }
                    else if (a2dp == null || headset == null) start() else refreshFromProxies()
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val device = intent.device() ?: return
                    if (!isAppleAudioAccessory(device)) return
                    val profile = if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) AudioProfile.A2DP else AudioProfile.HEADSET
                    when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)) {
                        BluetoothProfile.STATE_CONNECTED -> onProfileConnected(device, profile)
                        BluetoothProfile.STATE_DISCONNECTED -> onProfileDisconnected(device, profile)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> intent.device()?.let { d ->
                    _connected.value[d.address]?.let { disconnect(it.address, it.name) }
                }
                BluetoothDevice.ACTION_NAME_CHANGED -> intent.device()?.let { d ->
                    val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    _connected.update { m -> m[d.address]?.let { m + (d.address to it.copy(name = name)) } ?: m }
                }
                ACTION_BATTERY_LEVEL_CHANGED -> intent.device()?.let { d ->
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    _connected.update { m ->
                        m[d.address]?.let { m + (d.address to it.copy(hfpBatteryPercent = level.takeIf { l -> l in 0..100 })) } ?: m
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onProfileConnected(device: BluetoothDevice, profile: AudioProfile) {
        var newlyConnected: ConnectedAccessory? = null
        _connected.update { m ->
            val existing = m[device.address]
            val name = runCatching { device.name }.getOrNull()
            if (existing == null) {
                val created = ConnectedAccessory(device.address, name, setOf(profile), null, System.currentTimeMillis())
                newlyConnected = created
                m + (device.address to created)
            } else {
                m + (device.address to existing.copy(profiles = existing.profiles + profile, name = name ?: existing.name))
            }
        }
        newlyConnected?.let { _events.tryEmit(SystemBluetoothEvent.Connected(it)) }
    }

    private fun onProfileDisconnected(device: BluetoothDevice, profile: AudioProfile) {
        val existing = _connected.value[device.address] ?: return
        val remaining = existing.profiles - profile
        if (remaining.isEmpty()) disconnect(existing.address, existing.name)
        else _connected.update { it + (device.address to existing.copy(profiles = remaining)) }
    }

    private fun disconnect(address: String, name: String?) {
        if (_connected.value.containsKey(address)) {
            _connected.update { it - address }
            _events.tryEmit(SystemBluetoothEvent.Disconnected(address, name))
        }
    }

    private fun Intent.device(): BluetoothDevice? =
        IntentCompat.getParcelableExtra(this, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    companion object {
        private const val TAG = "SystemBtMonitor"

        /**
         * System broadcast carrying the HFP battery indicator. The action string is stable since
         * Android 8.1 but is not part of the public SDK constants, so it is declared here and
         * treated as best-effort: when a device does not send it, battery comes from BLE/AAP.
         */
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        /** Service UUID Apple audio accessories publish in their SDP record (AAP). */
        val AAP_SERVICE_UUID: ParcelUuid by lazy { ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a") }

        @SuppressLint("MissingPermission")
        fun isAppleAudioAccessory(device: BluetoothDevice): Boolean {
            val uuids = runCatching { device.uuids }.getOrNull()
            if (uuids?.contains(AAP_SERVICE_UUID) == true) return true
            val name = runCatching { device.name }.getOrNull()?.lowercase() ?: return false
            return NAME_HINTS.any { it in name }
        }

        private val NAME_HINTS = listOf("airpods", "beats", "powerbeats")
    }
}
