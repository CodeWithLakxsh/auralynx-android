package app.auralynx.core.bluetooth.aap

import kotlinx.coroutines.flow.Flow

/** A connected AAP channel. One element of [incoming] is one L2CAP packet. */
interface AapConnection {
    val incoming: Flow<ByteArray>
    suspend fun send(packet: ByteArray)
    fun close()
}

/** Why the AAP channel could not be opened. Surfaced verbatim in the UI and diagnostics. */
sealed class ChannelError(message: String) : Exception(message) {
    /** Android < 16, or Android 16 builds that reject classic L2CAP socket settings. */
    class OsUnsupported(detail: String) : ChannelError(detail)
    class PermissionMissing : ChannelError("BLUETOOTH_CONNECT permission not granted")
    /** The accessory refused or did not answer (common when the phone's stack lacks the upstream fix). */
    class ConnectFailed(detail: String) : ChannelError(detail)
    class Timeout : ChannelError("The accessory did not respond in time")
}

/** Opens AAP connections to a device identified by its Bluetooth address. */
interface AapTransport {
    /** Whether this OS can, in principle, open the channel. Does not touch the radio. */
    fun osSupport(): OsSupport

    suspend fun connect(address: String): AapConnection

    enum class OsSupport { SUPPORTED, UNSUPPORTED, UNKNOWN_UNTIL_ATTEMPTED }
}
