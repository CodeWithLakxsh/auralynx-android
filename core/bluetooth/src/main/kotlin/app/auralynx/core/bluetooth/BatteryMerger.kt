package app.auralynx.core.bluetooth

import app.auralynx.core.bluetooth.ble.BleSighting
import app.auralynx.core.protocol.ble.ProximityCrypto
import app.auralynx.core.protocol.devices.AccessoryModel
import app.auralynx.core.protocol.devices.FormFactor
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.BatteryReading
import app.auralynx.core.protocol.model.ChargingState
import app.auralynx.core.protocol.model.MeasurementSource

/** Combines battery readings from different sources without inventing values. */
object BatteryMerger {

    fun merge(
        aap: Map<BatteryComponent, BatteryReading>,
        ble: Map<BatteryComponent, BatteryReading>,
        hfpPercent: Int?,
        model: AccessoryModel,
    ): Map<BatteryComponent, BatteryReading> {
        val out = ble.toMutableMap()
        // AAP is 1 % precise and authoritative for every component it reports.
        out.putAll(aap)
        if (out.isEmpty() && hfpPercent != null) {
            // HFP reports a single combined value; it is never split into left/right.
            out[BatteryComponent.SINGLE] =
                BatteryReading(BatteryComponent.SINGLE, hfpPercent, ChargingState.UNKNOWN, MeasurementSource.STANDARD_BLUETOOTH)
        }
        return out
    }

    /** Converts a public (10 %) or decrypted (1 %) proximity advertisement into readings. */
    fun fromBle(s: BleSighting, model: AccessoryModel, encryptionKey: ByteArray? = null): Map<BatteryComponent, BatteryReading> {
        val st = s.status
        fun reading(c: BatteryComponent, level: Int?, charging: Boolean, src: MeasurementSource) =
            level?.let { c to BatteryReading(c, it, if (charging) ChargingState.CHARGING else ChargingState.DISCHARGING, src) }

        val decrypted = encryptionKey?.let { key -> st.encryptedPayload?.let { ProximityCrypto.decryptBattery(it, key) } }
        val single = model.formFactor != FormFactor.EARBUDS_WITH_CASE
        if (decrypted != null) {
            val flipped = !st.leftIsPrimary
            return if (single) {
                listOfNotNull(decrypted[0]?.let { reading(BatteryComponent.SINGLE, it.level, it.charging, MeasurementSource.BLE_ENCRYPTED) }).toMap()
            } else {
                val left = if (flipped) decrypted[1] else decrypted[0]
                val right = if (flipped) decrypted[0] else decrypted[1]
                listOfNotNull(
                    left?.let { reading(BatteryComponent.LEFT, it.level, it.charging, MeasurementSource.BLE_ENCRYPTED) },
                    right?.let { reading(BatteryComponent.RIGHT, it.level, it.charging, MeasurementSource.BLE_ENCRYPTED) },
                    decrypted[2]?.let { reading(BatteryComponent.CASE, it.level, it.charging, MeasurementSource.BLE_ENCRYPTED) },
                ).toMap()
            }
        }
        val src = MeasurementSource.BLE_ADVERTISEMENT
        return if (single) {
            listOfNotNull(reading(BatteryComponent.SINGLE, st.singleBattery, st.leftCharging, src)).toMap()
        } else {
            listOfNotNull(
                reading(BatteryComponent.LEFT, st.leftBattery, st.leftCharging, src),
                reading(BatteryComponent.RIGHT, st.rightBattery, st.rightCharging, src),
                reading(BatteryComponent.CASE, st.caseBattery, st.caseCharging, src),
            ).toMap()
        }
    }
}
