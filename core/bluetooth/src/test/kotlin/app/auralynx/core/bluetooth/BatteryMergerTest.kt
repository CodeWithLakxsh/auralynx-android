package app.auralynx.core.bluetooth

import app.auralynx.core.bluetooth.ble.BleSighting
import app.auralynx.core.common.Hex
import app.auralynx.core.protocol.ble.Continuity
import app.auralynx.core.protocol.ble.ProximityStatus
import app.auralynx.core.protocol.devices.AccessoryModel
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.BatteryReading
import app.auralynx.core.protocol.model.ChargingState
import app.auralynx.core.protocol.model.MeasurementSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryMergerTest {
    private fun sighting(status: String, battery: String, flags: String, enc: String = " 00".repeat(16)): BleSighting {
        val value = Continuity.parse(Hex.decode("07 19 01 14 20 $status $battery $flags 00 00 00$enc")).single().value
        return BleSighting("70:81:94:0D:FB:AA", -50, 0, ProximityStatus.parse(value)!!, AccessoryModel.AIRPODS_PRO_2, null)
    }

    @Test fun publicAdvert_givesTenPercentReadings() {
        val r = BatteryMerger.fromBle(sighting("2B", "98", "35"), AccessoryModel.AIRPODS_PRO_2)
        assertEquals(80, r.getValue(BatteryComponent.LEFT).level)
        assertEquals(90, r.getValue(BatteryComponent.RIGHT).level)
        assertEquals(50, r.getValue(BatteryComponent.CASE).level)
        assertTrue(r.values.all { it.source == MeasurementSource.BLE_ADVERTISEMENT })
    }

    @Test fun encryptedAdvert_withKey_givesOnePercentReadings() {
        // ciphertext of plaintext 00 D5 50 23 ... under key 00112233..ff (see ProximityCryptoTest)
        val s = sighting("2B", "98", "35", enc = " " + "6fac16f8e3ac994fb32d250a49231b48".chunked(2).joinToString(" "))
        val r = BatteryMerger.fromBle(s, AccessoryModel.AIRPODS_PRO_2, Hex.decode("00112233445566778899aabbccddeeff"))
        assertEquals(85, r.getValue(BatteryComponent.LEFT).level)
        assertEquals(ChargingState.CHARGING, r.getValue(BatteryComponent.LEFT).charging)
        assertEquals(80, r.getValue(BatteryComponent.RIGHT).level)
        assertEquals(35, r.getValue(BatteryComponent.CASE).level)
        assertTrue(r.values.all { it.source == MeasurementSource.BLE_ENCRYPTED })
    }

    @Test fun accessoryChannel_overridesBle() {
        val aap = mapOf(BatteryComponent.LEFT to BatteryReading(BatteryComponent.LEFT, 77, ChargingState.DISCHARGING, MeasurementSource.ACCESSORY_CHANNEL))
        val ble = BatteryMerger.fromBle(sighting("2B", "98", "35"), AccessoryModel.AIRPODS_PRO_2)
        val merged = BatteryMerger.merge(aap, ble, 40, AccessoryModel.AIRPODS_PRO_2)
        assertEquals(77, merged.getValue(BatteryComponent.LEFT).level)
        assertEquals(MeasurementSource.BLE_ADVERTISEMENT, merged.getValue(BatteryComponent.RIGHT).source)
        assertTrue("HFP is ignored when better data exists", BatteryComponent.SINGLE !in merged)
    }

    @Test fun hfpOnly_isReportedAsSingleCombinedValue() {
        val merged = BatteryMerger.merge(emptyMap(), emptyMap(), 60, AccessoryModel.AIRPODS_2)
        assertEquals(setOf(BatteryComponent.SINGLE), merged.keys)
        assertEquals(MeasurementSource.STANDARD_BLUETOOTH, merged.getValue(BatteryComponent.SINGLE).source)
    }
}
