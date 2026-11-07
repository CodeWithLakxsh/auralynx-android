package app.auralynx.core.protocol.ble

import app.auralynx.core.common.u8

/** Charging-case lid state. Only trustworthy when broadcast by a bud that is inside the case. */
enum class LidState { OPEN, CLOSED, NOT_IN_CASE, UNKNOWN }

/**
 * Decoded *public* part of a Proximity Pairing (type 0x07) advertisement.
 *
 * Public battery values have 10 % resolution. The remaining 16 bytes of the message are encrypted
 * with a per-accessory key; see [ProximityCrypto.decryptBattery].
 *
 * Byte layout of the 25-byte value (offsets relative to the value):
 * ```
 * 0      prefix (0x01 for status broadcasts)
 * 1..2   model code, big-endian (e.g. 0x0E20 = AirPods Pro)
 * 3      status flags
 * 4      battery nibbles (low = primary-side bud, high = other bud)
 * 5      high nibble: charging flags, low nibble: case battery
 * 6      lid / counter byte
 * 7      colour
 * 8      suffix
 * 9..24  encrypted payload
 * ```
 */
data class ProximityStatus(
    val modelCode: Int,
    val leftBattery: Int?,
    val rightBattery: Int?,
    /** For single-unit devices (AirPods Max, most Beats) this is the headset battery. */
    val singleBattery: Int?,
    val caseBattery: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    val leftIsPrimary: Boolean,
    val lid: LidState,
    val colorCode: Int,
    val encryptedPayload: ByteArray?,
    val rawStatus: Int,
) {
    override fun equals(other: Any?): Boolean = other is ProximityStatus && other.modelCode == modelCode &&
        other.rawStatus == rawStatus && other.leftBattery == leftBattery && other.rightBattery == rightBattery &&
        other.caseBattery == caseBattery && other.lid == lid &&
        (other.encryptedPayload?.contentEquals(encryptedPayload ?: ByteArray(0)) ?: (encryptedPayload == null))

    override fun hashCode(): Int = modelCode * 31 + rawStatus

    companion object {
        const val MESSAGE_LENGTH = 25
        private const val UNKNOWN_NIBBLE = 15

        /** Parses a Proximity Pairing message value, or returns null if it is not a status broadcast. */
        fun parse(value: ByteArray): ProximityStatus? {
            if (value.size < 9 || value.u8(0) != 0x01) return null
            val model = (value.u8(1) shl 8) or value.u8(2)
            val status = value.u8(3)
            val battery = value.u8(4)
            val flagsAndCase = value.u8(5)

            val leftPrimary = status.bit(5)
            val flipped = !leftPrimary
            val thisInCase = status.bit(6)
            val oneInCase = status.bit(4)
            val bothInCase = status.bit(2)

            val lowNibble = battery and 0x0F
            val highNibble = (battery shr 4) and 0x0F
            val chargeFlags = (flagsAndCase shr 4) and 0x0F

            val left = if (flipped) highNibble else lowNibble
            val right = if (flipped) lowNibble else highNibble
            val earSwap = flipped xor thisInCase

            val lid = when {
                !(thisInCase || oneInCase || bothInCase) -> LidState.NOT_IN_CASE
                !(thisInCase || bothInCase) -> LidState.UNKNOWN
                (value.u8(6) shr 3) and 0x01 == 0 -> LidState.OPEN
                else -> LidState.CLOSED
            }

            return ProximityStatus(
                modelCode = model,
                leftBattery = nibbleToPercent(left),
                rightBattery = nibbleToPercent(right),
                singleBattery = nibbleToPercent(lowNibble),
                caseBattery = nibbleToPercent(flagsAndCase and 0x0F),
                leftCharging = if (flipped) chargeFlags.bit(1) else chargeFlags.bit(0),
                rightCharging = if (flipped) chargeFlags.bit(0) else chargeFlags.bit(1),
                caseCharging = chargeFlags.bit(2),
                leftInEar = if (earSwap) status.bit(3) else status.bit(1),
                rightInEar = if (earSwap) status.bit(1) else status.bit(3),
                leftIsPrimary = leftPrimary xor thisInCase,
                lid = lid,
                colorCode = value.u8(7),
                encryptedPayload = if (value.size >= MESSAGE_LENGTH) value.copyOfRange(9, 25) else null,
                rawStatus = status,
            )
        }

        /** Nibble 0..10 maps to 0..100 %, 15 means "not reported"; values 11..14 clamp to 100. */
        internal fun nibbleToPercent(n: Int): Int? = when {
            n == UNKNOWN_NIBBLE -> null
            n > 10 -> 100
            else -> n * 10
        }

        private fun Int.bit(n: Int): Boolean = (this shr n) and 1 == 1
    }
}
