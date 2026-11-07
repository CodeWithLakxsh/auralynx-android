package app.auralynx.core.bluetooth.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Which runtime permissions Bluetooth features need on this API level. */
object BluetoothPermissions {
    /** Needed to see connected/bonded devices and to open the accessory channel. */
    val connect: List<String>
        get() = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    /**
     * Needed for BLE proximity scanning. On Android 12+ the manifest declares BLUETOOTH_SCAN with
     * `neverForLocation`, so no location permission is involved. Android 8-11 require location.
     */
    val scan: List<String>
        get() = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    val all: List<String> get() = (connect + scan).distinct()

    fun hasConnect(context: Context) = connect.all { context.granted(it) }
    fun hasScan(context: Context) = scan.all { context.granted(it) }

    private fun Context.granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
