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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AAP transport over a classic (BR/EDR) L2CAP channel on PSM 0x1001, using **only public SDK APIs**.
 *
 * Status as of Android 17 QPR2 (API 37.2), verified against the platform sources:
 * `BluetoothSocketSettings.Builder.setSocketType` accepts only `TYPE_RFCOMM` and `TYPE_LE`, and
 * `setL2capPsm` accepts only 128..255 (LE dynamic PSMs). The classic-L2CAP factories
 * (`createL2capSocket`, `createInsecureL2capSocket`) are `@hide` non-SDK interfaces. There is
 * therefore no public way for an app to open the AAP channel today.
 *
 * Auralynx deliberately does not use reflection on hidden APIs or hidden-API bypass libraries.
 * Instead, [osSupport] *probes* the public builder at runtime (no radio activity). If a future
 * Android release accepts a classic L2CAP socket on PSM 0x1001, the channel lights up without an
 * app update; until then the accessory engine runs in BLE + standard-Bluetooth mode.
 * See docs/research/ROOTLESS_COMPATIBILITY.md.
 */
@Singleton
class L2capAapTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AapTransport {

    private val support: AapTransport.OsSupport by lazy { probePublicApi() }

    /** Human-readable result of the last probe, for diagnostics and the research screen. */
    @Volatile var probeDetail: String = "not probed"
        private set

    override fun osSupport(): AapTransport.OsSupport = support

    override fun supportDetail(): String { support; return probeDetail }

    @SuppressLint("Range") // Intentional: we probe whether the platform accepts the classic AAP PSM.
    private fun probePublicApi(): AapTransport.OsSupport {
        if (Build.VERSION.SDK_INT < 36) {
            probeDetail = "BluetoothSocketSettings requires API 36; this device runs API ${Build.VERSION.SDK_INT}"
            return AapTransport.OsSupport.UNSUPPORTED
        }
        return try {
            buildSettings()
            probeDetail = "Public API accepted TYPE_L2CAP with PSM 0x1001"
            AapTransport.OsSupport.SUPPORTED
        } catch (e: IllegalArgumentException) {
            probeDetail = "Public API rejects classic L2CAP on API ${Build.VERSION.SDK_INT}: ${e.message}"
            AapTransport.OsSupport.UNSUPPORTED
        }.also { Diag.i(TAG) { "Accessory channel probe: $probeDetail" } }
    }

    @SuppressLint("MissingPermission") // checked explicitly below
    override suspend fun connect(address: String): AapConnection = withContext(io) {
        if (osSupport() != AapTransport.OsSupport.SUPPORTED || Build.VERSION.SDK_INT < 36) {
            throw ChannelError.OsUnsupported(probeDetail)
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw ChannelError.PermissionMissing()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw ChannelError.ConnectFailed("No Bluetooth adapter")
        val socket = try {
            adapter.getRemoteDevice(address).createUsingSocketSettings(buildSettings())
        } catch (e: IllegalArgumentException) {
            throw ChannelError.OsUnsupported(e.message ?: "Rejected by the platform")
        } catch (e: IOException) {
            throw ChannelError.ConnectFailed(e.message ?: "Socket creation failed")
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

    @SuppressLint("Range")
    @RequiresApi(36)
    private fun buildSettings(): BluetoothSocketSettings = BluetoothSocketSettings.Builder()
        .setSocketType(BluetoothSocket.TYPE_L2CAP)
        .setL2capPsm(AapConstants.PSM)
        .setAuthenticationRequired(false)
        .setEncryptionRequired(false)
        .build()

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
