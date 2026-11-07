package app.auralynx.core.bluetooth

import app.auralynx.core.bluetooth.aap.AapConnection
import app.auralynx.core.bluetooth.aap.AapTransport
import app.auralynx.core.bluetooth.aap.ChannelError
import app.auralynx.core.common.Hex
import app.auralynx.core.protocol.aap.AapCommands
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * Deterministic stand-in for an accessory, used only by unit tests. It is NOT hardware
 * validation: it replays packet shapes from public protocol documentation.
 */
class FakeAccessoryConnection(
    /** Given a packet the host sent, return the packets the "accessory" answers with. */
    private val responder: (ByteArray) -> List<ByteArray> = ::echoControlWrites,
) : AapConnection {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    val sent = mutableListOf<ByteArray>()
    var closed = false
        private set

    override val incoming: Flow<ByteArray> = inbox.consumeAsFlow()

    override suspend fun send(packet: ByteArray) {
        check(!closed) { "closed" }
        sent += packet
        responder(packet).forEach { inbox.send(it) }
    }

    fun push(hex: String) { inbox.trySend(Hex.decode(hex)) }

    /** Simulates the link dropping. */
    fun drop() { inbox.close(java.io.IOException("link lost")) }

    override fun close() { closed = true; inbox.close() }

    companion object {
        /** Firmware echoes control writes (opcode 0x0009) with the same bytes. */
        fun echoControlWrites(packet: ByteArray): List<ByteArray> =
            if (packet.size >= 6 && packet[4] == 0x09.toByte()) listOf(packet) else emptyList()

        fun silent(@Suppress("UNUSED_PARAMETER") packet: ByteArray): List<ByteArray> = emptyList()
    }
}

class FakeTransport(
    var support: AapTransport.OsSupport = AapTransport.OsSupport.SUPPORTED,
    /** Number of connect() calls that fail before one succeeds. */
    var failuresBeforeSuccess: Int = 0,
) : AapTransport {
    var attempts = 0
    val connections = mutableListOf<FakeAccessoryConnection>()

    override fun osSupport() = support

    override suspend fun connect(address: String): AapConnection {
        attempts++
        if (attempts <= failuresBeforeSuccess) throw ChannelError.ConnectFailed("refused (fake)")
        return FakeAccessoryConnection().also { connections += it }
    }
}

internal val HANDSHAKE_HEX = Hex.encode(AapCommands.handshake())
