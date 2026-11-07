package app.auralynx.core.protocol

import app.auralynx.core.common.Hex
import app.auralynx.core.protocol.aap.AapEvent
import app.auralynx.core.protocol.aap.AapParser
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.Bud
import app.auralynx.core.protocol.model.BudPlacement
import app.auralynx.core.protocol.model.ChargingState
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.MeasurementSource
import app.auralynx.core.protocol.model.PressType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapParserTest {

    private fun parse(hex: String) = AapParser.parse(Hex.decode(hex))

    @Test fun battery_documentedAirPodsPro2Packet() {
        val event = parse("04 00 04 00 04 00 03 02 01 64 02 01 04 01 63 01 01 08 01 11 02 01") as AapEvent.Battery
        assertEquals(3, event.readings.size)
        val right = event.readings.first { it.component == BatteryComponent.RIGHT }
        val left = event.readings.first { it.component == BatteryComponent.LEFT }
        val case = event.readings.first { it.component == BatteryComponent.CASE }
        assertEquals(100, right.level); assertEquals(ChargingState.DISCHARGING, right.charging)
        assertEquals(99, left.level); assertEquals(ChargingState.CHARGING, left.charging)
        assertEquals(17, case.level); assertEquals(ChargingState.DISCHARGING, case.charging)
        assertTrue(event.readings.all { it.source == MeasurementSource.ACCESSORY_CHANNEL })
    }

    @Test fun battery_dropsPlaceholderAndDisconnectedValues() {
        // case reports 0x7F placeholder, right reports "disconnected"
        val event = parse("04 00 04 00 04 00 03 08 01 7F 02 01 02 01 32 04 01 04 01 50 02 01") as AapEvent.Battery
        assertEquals(listOf(BatteryComponent.LEFT), event.readings.map { it.component })
        assertEquals(80, event.readings.single().level)
    }

    @Test fun battery_truncatedPacketDoesNotThrow() {
        val event = parse("04 00 04 00 04 00 03 02 01 64") as AapEvent.Battery
        assertTrue(event.readings.isEmpty())
    }

    @Test fun earDetection() {
        val event = parse("04 00 04 00 06 00 00 02") as AapEvent.EarDetection
        assertEquals(BudPlacement.IN_EAR, event.primary)
        assertEquals(BudPlacement.IN_CASE, event.secondary)
    }

    @Test fun listeningModeNotification() {
        assertEquals(AapEvent.ListeningModeChanged(ListeningMode.TRANSPARENCY), parse("04 00 04 00 09 00 0D 03 00 00 00"))
        assertEquals(AapEvent.ListeningModeChanged(ListeningMode.ADAPTIVE), parse("04 00 04 00 09 00 0D 04 00 00 00"))
    }

    @Test fun conversationAwarenessSetting() {
        assertEquals(AapEvent.ConversationAwarenessSetting(true), parse("04 00 04 00 09 00 28 01 00 00 00"))
        assertEquals(AapEvent.ConversationAwarenessSetting(false), parse("04 00 04 00 09 00 28 02 00 00 00"))
    }

    @Test fun unknownControl_isPreservedRaw() {
        val event = parse("04 00 04 00 09 00 77 05 06 07 08") as AapEvent.ControlValue
        assertEquals(0x77, event.identifier)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), event.value)
    }

    @Test fun conversationActivity() {
        val speaking = parse("04 00 04 00 4B 00 02 00 01 01") as AapEvent.ConversationActivity
        assertTrue(speaking.speaking)
        val stopped = parse("04 00 04 00 4B 00 02 00 01 03") as AapEvent.ConversationActivity
        assertEquals(false, stopped.speaking)
    }

    @Test fun deviceInfo_documentedCapture() {
        val hex = "040004001d0002d5000400416972506f64732050726f004133303438004170706c6520496e632e0051584e524848595850360036312e313836383034303030323030303030302e323731330036312e313836383034303030323030303030302e3237313300312e302e3000636f6d2e6170706c652e6163636573736f72792e757064617465722e6170702e3731004859394c5432454632364a59004833504c5748444a32364b3000363335373533360089312a6567a5400f84a3ca234947efd40b90d78436ae5946748d70273e66066a2589300035333935303630363400"
        val info = parse(hex) as AapEvent.DeviceInfo
        assertEquals("AirPods Pro", info.name)
        assertEquals("A3048", info.modelNumber)
        assertEquals("Apple Inc.", info.manufacturer)
        assertEquals("QXNRHHYXP6", info.serialNumber)
        assertEquals("61.1868040002000000.2713", info.firmwareVersion)
        assertEquals("HY9LT2EF26JY", info.leftSerial)
        assertEquals("H3PLWHDJ26K0", info.rightSerial)
    }

    @Test fun stemPress() {
        assertEquals(AapEvent.StemPress(PressType.DOUBLE, Bud.RIGHT), parse("04 00 04 00 19 00 06 02"))
        assertTrue(parse("04 00 04 00 19 00 09 02") is AapEvent.Unknown)
    }

    @Test fun proximityKeys() {
        val irk = "00112233445566778899AABBCCDDEEFF"
        val enc = "FFEEDDCCBBAA99887766554433221100"
        val event = parse("04 00 04 00 31 00 02 01 00 10 00 $irk 04 00 10 00 $enc") as AapEvent.ProximityKeys
        assertArrayEquals(Hex.decode(irk), event.irk)
        assertArrayEquals(Hex.decode(enc), event.encryptionKey)
    }

    @Test fun nonDataPacket_returnsNull() {
        assertNull(parse("00 00 04 00 01 00"))
        assertNull(parse("04 00"))
    }

    @Test fun unknownOpcode_isUnknown() {
        val event = parse("04 00 04 00 2E 00 01 02") as AapEvent.Unknown
        assertEquals(0x2E, event.opcode)
    }
}
