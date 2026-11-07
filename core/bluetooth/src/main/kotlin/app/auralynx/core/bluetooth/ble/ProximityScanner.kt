package app.auralynx.core.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import app.auralynx.core.bluetooth.system.BluetoothPermissions
import app.auralynx.core.common.Diag
import app.auralynx.core.protocol.ble.Continuity
import app.auralynx.core.protocol.ble.ProximityStatus
import app.auralynx.core.protocol.devices.AccessoryModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One received proximity advertisement. */
data class BleSighting(
    val address: String,
    val rssi: Int,
    val timeMillis: Long,
    val status: ProximityStatus,
    val model: AccessoryModel?,
    val txPower: Int?,
)

/** Any Apple Continuity advertisement (used by the research module). */
data class ContinuitySighting(val address: String, val rssi: Int, val timeMillis: Long, val messages: List<Continuity.Message>)

enum class ScanIntensity {
    /** Background monitoring. Lowest radio duty cycle; Android may batch results. */
    LOW_POWER,

    /** Foreground finder screen only. */
    LOW_LATENCY,
}

/**
 * Passive BLE scanner for Apple proximity-pairing advertisements. Scanning stops when the flow
 * collector goes away, so a scan never outlives the screen or service that needs it.
 */
@Singleton
class ProximityScanner @Inject constructor(@ApplicationContext private val context: Context) {

    @SuppressLint("MissingPermission")
    fun proximity(intensity: ScanIntensity): Flow<BleSighting> {
        if (!BluetoothPermissions.hasScan(context)) return emptyFlow()
        val filter = ScanFilter.Builder()
            .setManufacturerData(Continuity.APPLE_COMPANY_ID, byteArrayOf(Continuity.TYPE_PROXIMITY_PAIRING.toByte()), byteArrayOf(0xFF.toByte()))
            .build()
        return scan(listOf(filter), intensity) { result ->
            val data = result.scanRecord?.getManufacturerSpecificData(Continuity.APPLE_COMPANY_ID) ?: return@scan null
            val message = Continuity.parse(data).firstOrNull { it.type == Continuity.TYPE_PROXIMITY_PAIRING } ?: return@scan null
            val status = ProximityStatus.parse(message.value) ?: return@scan null
            BleSighting(
                address = result.device.address,
                rssi = result.rssi,
                timeMillis = System.currentTimeMillis(),
                status = status,
                model = AccessoryModel.fromBleModelCode(status.modelCode),
                txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT },
            )
        }
    }

    /** All Apple Continuity messages; research use only, always foreground. */
    @SuppressLint("MissingPermission")
    fun continuity(): Flow<ContinuitySighting> {
        if (!BluetoothPermissions.hasScan(context)) return emptyFlow()
        val filter = ScanFilter.Builder().setManufacturerData(Continuity.APPLE_COMPANY_ID, ByteArray(0)).build()
        return scan(listOf(filter), ScanIntensity.LOW_LATENCY) { result ->
            val data = result.scanRecord?.getManufacturerSpecificData(Continuity.APPLE_COMPANY_ID) ?: return@scan null
            ContinuitySighting(result.device.address, result.rssi, System.currentTimeMillis(), Continuity.parse(data))
        }
    }

    @SuppressLint("MissingPermission")
    private fun <T : Any> scan(filters: List<ScanFilter>, intensity: ScanIntensity, map: (ScanResult) -> T?): Flow<T> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                runCatching { map(result) }.getOrNull()?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Diag.w(TAG) { "BLE scan failed: $errorCode" }
                close(IllegalStateException("BLE scan failed with code $errorCode"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(if (intensity == ScanIntensity.LOW_LATENCY) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(if (intensity == ScanIntensity.LOW_POWER) 2_000 else 0)
            .build()
        runCatching { scanner.startScan(filters, settings, callback) }.onFailure { close(it) }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    companion object { private const val TAG = "ProximityScanner" }
}
