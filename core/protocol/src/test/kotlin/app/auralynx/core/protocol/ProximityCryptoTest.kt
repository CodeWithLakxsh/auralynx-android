package app.auralynx.core.protocol

import app.auralynx.core.common.Hex
import app.auralynx.core.common.toHex
import app.auralynx.core.protocol.ble.ProximityCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityCryptoTest {

    /** Bluetooth Core Specification, Vol 3, Part H, Appendix D.7 (ah sample data). */
    private val specIrk = Hex.decode("ec0234a357c8ad05341010a60a397d9b")

    @Test fun ah_matchesBluetoothSpecVector() {
        assertEquals("0D FB AA", ProximityCrypto.ah(specIrk, Hex.decode("708194")).toHex())
    }

    @Test fun rpa_resolvesWithCorrectIrkOnly() {
        assertTrue(ProximityCrypto.resolvesTo("70:81:94:0D:FB:AA", specIrk))
        assertFalse(ProximityCrypto.resolvesTo("70:81:94:0D:FB:AB", specIrk))
        assertFalse(ProximityCrypto.resolvesTo("70:81:94:0D:FB:AA", ByteArray(16)))
    }

    @Test fun rpa_rejectsNonResolvableAddresses() {
        // Top bits 0b11 => static random address, not resolvable
        assertFalse(ProximityCrypto.resolvesTo("F0:81:94:0D:FB:AA", specIrk))
        assertFalse(ProximityCrypto.resolvesTo("not-an-address", specIrk))
    }

    /** Ciphertext produced with `openssl enc -aes-128-ecb -K 00112233445566778899aabbccddeeff -nopad`. */
    @Test fun decryptBattery_decodesLevelsAndChargingBit() {
        val key = Hex.decode("00112233445566778899aabbccddeeff")
        val cipher = Hex.decode("6fac16f8e3ac994fb32d250a49231b48")
        val values = ProximityCrypto.decryptBattery(cipher, key)
        assertNotNull(values)
        assertEquals(ProximityCrypto.EncryptedBattery(85, true), values!![0])
        assertEquals(ProximityCrypto.EncryptedBattery(80, false), values[1])
        assertEquals(ProximityCrypto.EncryptedBattery(35, false), values[2])
    }
}
