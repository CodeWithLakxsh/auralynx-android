package app.auralynx.core.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonTest {
    @Test fun hex_roundTrip() {
        val bytes = byteArrayOf(0x04, 0x00, 0xFF.toByte(), 0x1A)
        assertEquals("04 00 FF 1A", bytes.toHex())
        assertArrayEquals(bytes, Hex.decode("04:00:ff:1a"))
    }

    @Test fun littleEndianReads() {
        val b = byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte())
        assertEquals(0x1234, b.u16le(0))
        assertEquals(-2, b.s16le(2))
    }

    @Test fun redaction_masksAddressesAndSerials() {
        assertEquals("XX:XX:XX:XX:XX:9F", Redaction.address("a4:83:e7:11:22:9f"))
        val text = Redaction.text("Connected to A4:83:E7:11:22:9F serial QXNRHHYXP6 model A3048")
        assertFalse(text.contains("A4:83"))
        assertFalse(text.contains("QXNRHHYXP6"))
        assertTrue(text.contains("XX:XX:XX:XX:XX:9F"))
        assertTrue("model numbers are not secrets", text.contains("A3048"))
    }

    @Test fun diag_appliesRedactionBeforeSinks() {
        val captured = mutableListOf<Diag.Entry>()
        val sink = Diag.Sink { captured += it }
        Diag.addSink(sink)
        try {
            Diag.i("Test") { "device 00:11:22:33:44:55" }
        } finally {
            Diag.removeSink(sink)
        }
        assertEquals("device XX:XX:XX:XX:XX:55", captured.single().message)
    }
}
