package app.auralynx.core.protocol.ble

import app.auralynx.core.common.u8
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic helpers for identifying the user's own accessory in BLE scans.
 *
 * Keys are obtained from the accessory over the AAP channel (opcode 0x30/0x31) and stored only on
 * this device, encrypted with an Android Keystore key.
 */
object ProximityCrypto {

    /**
     * Resolvable Private Address check, Bluetooth Core Specification Vol 3, Part H, 2.2.2:
     * `hash == ah(IRK, prand)` where `ah(k, r) = e(k, r') mod 2^24` and r' is prand zero-padded to
     * 128 bits. [address] is the textual address, most-significant octet first.
     */
    fun resolvesTo(address: String, irk: ByteArray): Boolean {
        require(irk.size == 16) { "IRK must be 16 bytes" }
        val octets = parseAddress(address) ?: return false
        // Resolvable private addresses have the two most significant bits set to 0b01.
        if ((octets[0].toInt() and 0xC0) != 0x40) return false
        val prand = octets.copyOfRange(0, 3)
        val hash = octets.copyOfRange(3, 6)
        return ah(irk, prand).contentEquals(hash)
    }

    /** Implements `ah` with big-endian inputs/outputs as written in the specification. */
    fun ah(irk: ByteArray, prand: ByteArray): ByteArray {
        require(prand.size == 3)
        val block = ByteArray(16)
        prand.copyInto(block, destinationOffset = 13)
        val encrypted = aesEcb(irk, block, Cipher.ENCRYPT_MODE)
        return encrypted.copyOfRange(13, 16)
    }

    /** Decrypted battery for one component of the encrypted proximity payload. */
    data class EncryptedBattery(val level: Int, val charging: Boolean)

    /**
     * Decrypts the 16-byte encrypted section of a proximity advertisement. Positions 1, 2 and 3 of
     * the plaintext hold the low-nibble bud (left unless the status flip bit says otherwise), the
     * other bud, and the case (bit 7 = charging,
     * bits 0-6 = percent). Returns null when the key does not produce plausible values.
     */
    fun decryptBattery(encrypted: ByteArray, key: ByteArray): List<EncryptedBattery?>? {
        if (encrypted.size != 16 || key.size != 16) return null
        val plain = runCatching { aesEcb(key, encrypted, Cipher.DECRYPT_MODE) }.getOrNull() ?: return null
        val values = (1..3).map { pos ->
            val raw = plain.u8(pos)
            val level = raw and 0x7F
            if (level > 100) null else EncryptedBattery(level, raw and 0x80 != 0)
        }
        return if (values.all { it == null }) null else values
    }

    private fun aesEcb(key: ByteArray, block: ByteArray, mode: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    private fun parseAddress(address: String): ByteArray? {
        val parts = address.split(':')
        if (parts.size != 6) return null
        return ByteArray(6) { i -> parts[i].toIntOrNull(16)?.toByte() ?: return null }
    }
}
