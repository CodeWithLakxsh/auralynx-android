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
import kotlinx.coroutines.withContext
import java.io.IOException
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
        val connection = connectSocket(socket, io, CONNECT_TIMEOUT_MS)
        Diag.i(TAG) { "AAP channel connected to $address" }
        connection
    }

    @SuppressLint("Range")
    @RequiresApi(36)
    private fun buildSettings(): BluetoothSocketSettings = BluetoothSocketSettings.Builder()
        .setSocketType(BluetoothSocket.TYPE_L2CAP)
        .setL2capPsm(AapConstants.PSM)
        .setAuthenticationRequired(false)
        .setEncryptionRequired(false)
        .build()

    companion object {
        private const val TAG = "L2capAapTransport"
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val MAX_PACKET = 1024
    }
}
