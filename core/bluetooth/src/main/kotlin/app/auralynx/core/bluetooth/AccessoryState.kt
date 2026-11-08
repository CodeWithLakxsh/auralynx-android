package app.auralynx.core.bluetooth

import app.auralynx.core.bluetooth.aap.AapState
import app.auralynx.core.bluetooth.ble.BleSighting
import app.auralynx.core.bluetooth.system.AudioProfile
import app.auralynx.core.protocol.devices.AccessoryCapabilities
import app.auralynx.core.protocol.devices.AccessoryModel
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.BatteryReading

/** State of the proprietary accessory channel for one device. */
sealed interface ChannelStatus {
    data object DisabledByUser : ChannelStatus
    data class OsUnsupported(val detail: String) : ChannelStatus
    data class Connecting(val attempt: Int) : ChannelStatus
    data object Connected : ChannelStatus
    /** [nextRetryAtMillis] null means Auralynx has stopped retrying until the next connection. */
    data class Failed(val reason: String, val nextRetryAtMillis: Long?) : ChannelStatus
    data object Idle : ChannelStatus
}

/** How the model was identified, strongest first. */
enum class ModelEvidence { DEVICE_INFORMATION, BLE_ADVERTISEMENT, REMEMBERED, BLUETOOTH_NAME, UNKNOWN }

/** How a BLE sighting was matched to this connected device. */
enum class BleMatch {
    /** The advertisement's resolvable private address resolved with this accessory's IRK. */
    KEY_VERIFIED,

    /** Same model as the connected accessory, strong signal, and it is the only candidate. */
    PROBABLE,
}

/**
 * [encryptionKey] is set only for [BleMatch.KEY_VERIFIED] sightings, so 1 % battery values are
 * decrypted exclusively for advertisements proven to come from this accessory.
 */
class BleSnapshot(
    val sighting: BleSighting,
    val match: BleMatch,
    val encryptionKey: ByteArray? = null,
    /** Buds in ear after debouncing (null until two consecutive advertisements agree). */
    val stableBudsInEar: Int? = null,
)

data class AccessoryState(
    val address: String,
    val name: String?,
    val model: AccessoryModel,
    val modelEvidence: ModelEvidence,
    val profiles: Set<AudioProfile>,
    val connectedAtMillis: Long,
    val channel: ChannelStatus,
    val aap: AapState?,
    val ble: BleSnapshot?,
    val hfpBatteryPercent: Int?,
    val capabilities: AccessoryCapabilities,
) {
    /** Best available reading per component; more precise sources win. */
    val battery: Map<BatteryComponent, BatteryReading>
        get() = BatteryMerger.merge(aap?.battery.orEmpty(), ble?.let { BatteryMerger.fromBle(it.sighting, model, it.encryptionKey) }.orEmpty(), hfpBatteryPercent, model)
}
