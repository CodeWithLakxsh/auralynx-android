package app.auralynx.core.common

/**
 * Redacts identifiers before they are written to diagnostics or exports.
 *
 * Bluetooth addresses keep only their last octet (enough to tell two devices apart in a log),
 * and serial-number-like tokens are masked.
 */
object Redaction {
    private val MAC = Regex("""\b([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})\b""")
    private val SERIAL = Regex("""\b(?=[A-Z0-9]{10,14}\b)(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]+\b""")

    fun address(address: String?): String {
        if (address.isNullOrBlank()) return "--"
        val parts = address.split(':', '-')
        return if (parts.size == 6) "XX:XX:XX:XX:XX:${parts.last().uppercase()}" else "<redacted>"
    }

    fun serial(serial: String?): String {
        if (serial.isNullOrBlank()) return "--"
        return if (serial.length <= 4) "****" else "****" + serial.takeLast(3)
    }

    /** Redacts every MAC address and serial-like token found in free text. */
    fun text(input: String): String {
        val noMac = MAC.replace(input) { m -> address(m.value) }
        return SERIAL.replace(noMac) { m -> serial(m.value) }
    }
}
