package app.auralynx.core.bluetooth.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects an already-created L2CAP [BluetoothSocket] and wraps it as an [AapConnection].
 *
 * `BluetoothSocket.connect()` blocks and ignores coroutine cancellation, so a watchdog closes the
 * socket after [timeoutMillis] to unblock it. Shared by every transport implementation.
 */
@SuppressLint("MissingPermission") // callers check BLUETOOTH_CONNECT before creating the socket
suspend fun connectSocket(socket: BluetoothSocket, io: CoroutineDispatcher, timeoutMillis: Long): AapConnection = withContext(io) {
    val timedOut = AtomicBoolean(false)
    coroutineScope {
        val watchdog = launch {
            delay(timeoutMillis)
            timedOut.set(true)
            runCatching { socket.close() }
        }
        try {
            socket.connect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            if (timedOut.get()) throw ChannelError.Timeout()
            throw ChannelError.ConnectFailed(e.message ?: "connect() failed")
        } finally {
            watchdog.cancel()
        }
    }
    SocketConnection(socket, io)
}

private class SocketConnection(private val socket: BluetoothSocket, private val io: CoroutineDispatcher) : AapConnection {
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)

    override val incoming: Flow<ByteArray> = flow {
        val input = socket.inputStream
        val buffer = ByteArray(MAX_PACKET)
        while (!closed.get()) {
            val n = try { input.read(buffer) } catch (e: IOException) { if (closed.get()) break else throw e }
            if (n < 0) break
            if (n > 0) emit(buffer.copyOf(n))
        }
    }.flowOn(io)

    override suspend fun send(packet: ByteArray) = writeLock.withLock {
        withContext(io) {
            socket.outputStream.write(packet)
            socket.outputStream.flush()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { socket.close() }
    }

    companion object { private const val MAX_PACKET = 1024 }
}
