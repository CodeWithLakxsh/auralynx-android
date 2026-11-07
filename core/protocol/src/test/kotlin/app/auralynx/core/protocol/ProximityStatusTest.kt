package app.auralynx.core.protocol

import app.auralynx.core.common.Hex
import app.auralynx.core.protocol.ble.Continuity
import app.auralynx.core.protocol.ble.LidState
import app.auralynx.core.protocol.ble.ProximityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityStatusTest {

    // 07 19 | 01 0E20 | status | batt | flags+case | lid | colour | suffix | 16 enc bytes
    private fun advert(status: String, battery: String, flags: String, lid: String = "00") =
        Hex.decode("07 19 01 0E 20 $status $battery $flags $lid 00 00" + " 00".repeat(16))

    @Test fun continuity_splitsTlvs() {
        val data = Hex.decode("10 02 AA BB") + advert("22", "8A", "34")
        val messages = Continuity.parse(data)
        assertEquals(listOf(0x10, 0x07), messages.map { it.type })
        assertEquals("Nearby Info", messages[0].typeName)
        assertEquals(25, messages[1].value.size)
    }

    @Test fun continuity_ignoresTruncatedTrailingTlv() {
        assertEquals(1, Continuity.parse(Hex.decode("10 01 AA 07 19 01")).size)
    }

    @Test fun leftPrimary_notFlipped() {
        // status 0x2B: bit5 (left primary), bit3 + bit1 (both in ear), bit0
        val value = Continuity.parse(advert("2B", "98", "34")).single().value
        val s = ProximityStatus.parse(value)!!
        assertEquals(0x0E20, s.modelCode)
        assertEquals(80, s.leftBattery)   // low nibble 8
        assertEquals(90, s.rightBattery)  // high nibble 9
        assertEquals(40, s.caseBattery)   // low nibble of 0x34
        // high nibble 0x3 = 0b0011 -> left and right charging, case (bit 2) not charging
        assertTrue(s.leftCharging)
        assertTrue(s.rightCharging)
        assertFalse(s.caseCharging)
        assertTrue(s.leftInEar)
        assertTrue(s.rightInEar)
        assertEquals(LidState.NOT_IN_CASE, s.lid)
    }

    @Test fun chargingFlags() {
        val s = ProximityStatus.parse(Continuity.parse(advert("20", "55", "75")).single().value)!!
        // high nibble 0x7: left (bit0), right (bit1), case (bit2) charging
        assertTrue(s.leftCharging); assertTrue(s.rightCharging); assertTrue(s.caseCharging)
        val none = ProximityStatus.parse(Continuity.parse(advert("20", "55", "05")).single().value)!!
        assertFalse(none.leftCharging); assertFalse(none.caseCharging)
    }

    @Test fun rightPrimary_valuesAreFlipped() {
        // bit5 clear -> right is primary, battery nibbles swap
        val s = ProximityStatus.parse(Continuity.parse(advert("02", "98", "0F")).single().value)!!
        assertEquals(90, s.leftBattery)
        assertEquals(80, s.rightBattery)
        assertNull(s.caseBattery) // 0xF = not reported
    }

    @Test fun lid_onlyTrustedFromBudInCase() {
        val open = ProximityStatus.parse(Continuity.parse(advert("64", "88", "08", lid = "00")).single().value)!!
        assertEquals(LidState.OPEN, open.lid)
        val closed = ProximityStatus.parse(Continuity.parse(advert("64", "88", "08", lid = "08")).single().value)!!
        assertEquals(LidState.CLOSED, closed.lid)
        // bit4 only: out-of-case bud reporting -> stale lid byte
        val stale = ProximityStatus.parse(Continuity.parse(advert("30", "88", "08", lid = "00")).single().value)!!
        assertEquals(LidState.UNKNOWN, stale.lid)
    }

    @Test fun nibbles() {
        assertEquals(100, ProximityStatus.nibbleToPercent(10))
        assertEquals(100, ProximityStatus.nibbleToPercent(12))
        assertNull(ProximityStatus.nibbleToPercent(15))
        assertEquals(0, ProximityStatus.nibbleToPercent(0))
    }

    @Test fun nonStatusPrefix_isRejected() {
        val value = Hex.decode("05 0E 20 00 00 00 00 00 00")
        assertNull(ProximityStatus.parse(value))
    }
}
