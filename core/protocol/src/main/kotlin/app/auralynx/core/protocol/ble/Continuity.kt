package app.auralynx.core.protocol.ble

import app.auralynx.core.common.u8

/**
 * Apple "Continuity" manufacturer-specific advertisement data (company identifier 0x004C).
 *
 * The payload is a sequence of TLVs: `[type][length][value...]`. Type names follow the public
 * research by Martin et al., "Handoff All Your Privacy" (PETS 2019) and the furiousMAC
 * continuity dissector. Auralynx only *decodes* message types; it never transmits them.
 */
object Continuity {
    const val APPLE_COMPANY_ID: Int = 0x004C

    data class Message(val type: Int, val value: ByteArray) {
        val typeName: String get() = TYPE_NAMES[type] ?: "Unknown (0x%02X)".format(type)

        override fun equals(other: Any?): Boolean = other is Message && other.type == type && other.value.contentEquals(value)
        override fun hashCode(): Int = 31 * type + value.contentHashCode()
    }

    const val TYPE_PROXIMITY_PAIRING: Int = 0x07

    val TYPE_NAMES: Map<Int, String> = mapOf(
        0x02 to "iBeacon",
        0x03 to "AirPrint",
        0x05 to "AirDrop",
        0x06 to "HomeKit",
        0x07 to "Proximity Pairing",
        0x08 to "Hey Siri",
        0x09 to "AirPlay Target",
        0x0A to "AirPlay Source",
        0x0B to "Magic Switch",
        0x0C to "Handoff",
        0x0D to "Tethering Target",
        0x0E to "Tethering Source",
        0x0F to "Nearby Action",
        0x10 to "Nearby Info",
        0x12 to "Find My",
    )

    /** Splits manufacturer data (without the 2-byte company id) into TLV messages. */
    fun parse(data: ByteArray): List<Message> {
        val out = mutableListOf<Message>()
        var i = 0
        while (i + 2 <= data.size) {
            val type = data.u8(i)
            val length = data.u8(i + 1)
            val end = i + 2 + length
            if (end > data.size) break
            out += Message(type, data.copyOfRange(i + 2, end))
            i = end
        }
        return out
    }
}
