package app.auralynx.core.common

/** Hex helpers used by protocol code, diagnostics and tests. */
object Hex {
    private val DIGITS = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray, separator: String = " "): String = buildString(bytes.size * 3) {
        bytes.forEachIndexed { index, b ->
            if (index > 0) append(separator)
            val v = b.toInt() and 0xFF
            append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
    }

    /** Parses "04 00 0A", "04000a" or "04:00:0A" into bytes. */
    fun decode(text: String): ByteArray {
        val clean = text.filter { it.isLetterOrDigit() }
        require(clean.length % 2 == 0) { "Hex string must contain an even number of digits" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

fun ByteArray.toHex(separator: String = " "): String = Hex.encode(this, separator)

/** Unsigned value of the byte at [index]. */
fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

/** Little-endian unsigned 16-bit value starting at [index]. */
fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

/** Little-endian signed 16-bit value starting at [index]. */
fun ByteArray.s16le(index: Int): Int = u16le(index).toShort().toInt()
