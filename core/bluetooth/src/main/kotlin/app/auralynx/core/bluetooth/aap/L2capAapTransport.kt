package app.auralynx.core.bluetooth.aap

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothSocketSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import app.auralynx.core.common.Diag
import app.auralynx.core.common.IoDispatcher
import app.auralynx.core.protocol.aap.AapConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AAP transport over a classic (BR/EDR) L2CAP channel, PSM 0x1001, using only the **public**
 * `BluetoothDevice.createUsingSocketSettings` API.
 *
 * - Android 17 (API 37) and later: classic L2CAP sockets are part of the public SDK.
 * - Android 16 (API 36): the API exists, but many builds reject `TYPE_L2CAP` with
 *   IllegalArgumentException. We try once and remember the answer.
 * - Android 15 and older: unsupported. Auralynx deliberately does not use non-SDK interfaces or
 *   hidden-API bypass libraries to work around this; see docs/research/ROOTLESS_COMPATIBILITY.md.
 */
@Singleton
class L2capAapTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AapTransport {

    @Volatile private var rejectedByOs: Boolean = false

    override fun osSupport(): AapTransport.OsSupport = when {
        Build.VERSION.SDK_INT >= 37 -> AapTransport.OsSupport.SUPPORTED
        Build.VERSION.SDK_INT == 36 && !rejectedByOs -> AapTransport.OsSupport.UNKNOWN_UNTIL_ATTEMPTED
        else -> AapTransport.OsSupport.UNSUPPORTED
    }

    @SuppressLint("MissingPermission") // checked explicitly below
    override suspend fun connect(address: String): AapConnection = withContext(io) {
        if (osSupport() == AapTransport.OsSupport.UNSUPPORTED) {
            throw ChannelError.OsUnsupported("Classic L2CAP sockets are not available to apps on Android ${Build.VERSION.RELEASE}")
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw ChannelError.PermissionMissing()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw ChannelError.ConnectFailed("No Bluetooth adapter")
        val device = adapter.getRemoteDevice(address)
        val socket = try {
            createSocket(device)
        } catch (e: IllegalArgumentException) {
            rejectedByOs = true
            throw ChannelError.OsUnsupported("This Android build rejects classic L2CAP socket settings: ${e.message}")
        }
        // socket.connect() blocks and ignores coroutine cancellation; a watchdog closes the socket to
        // unblock it when the accessory does not answer.
        val timedOut = AtomicBoolean(false)
        coroutineScope {
            val watchdog = launch {
                delay(CONNECT_TIMEOUT_MS)
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
        Diag.i(TAG) { "AAP channel connected to $address" }
        SocketConnection(socket, io)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(36)
    private fun createSocket(device: android.bluetooth.BluetoothDevice): BluetoothSocket {
        val settings = BluetoothSocketSettings.Builder()
            .setSocketType(BluetoothSocket.TYPE_L2CAP)
            .setL2capPsm(AapConstants.PSM)
            .setAuthenticationRequired(false)
            .setEncryptionRequired(false)
            .build()
        return device.createUsingSocketSettings(settings)
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
    }

    companion object {
        private const val TAG = "L2capAapTransport"
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val MAX_PACKET = 1024
    }
}
