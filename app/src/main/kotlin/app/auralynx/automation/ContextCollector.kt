package app.auralynx.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Looper
import android.os.Process
import app.auralynx.core.automation.MotionActivity
import app.auralynx.core.automation.Signal
import app.auralynx.core.data.db.PlaceEntity
import app.auralynx.system.LocationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects automation signals with event-driven, low-power sources only:
 * Wi-Fi via a network callback, location from the *passive* provider (fixes other apps already
 * requested) plus one-shot fixes at decision points, motion from the step-detector sensor (no
 * continuous accelerometer use), and foreground app from usage events when the user granted
 * Usage access. Each source starts only when its permission is present and a rule needs it.
 */
@Singleton
class ContextCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val location: LocationHelper,
) {
    private val _wifiSsid = MutableStateFlow<String?>(null)
    val wifiSsid: StateFlow<String?> = _wifiSsid.asStateFlow()
    private val _location = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _location.asStateFlow()
    private val _activity = MutableStateFlow<MotionActivity?>(null)
    val activity: StateFlow<MotionActivity?> = _activity.asStateFlow()
    private val _tick = MutableStateFlow(0L)
    /** Bumps whenever an asynchronous signal changes, to trigger re-evaluation. */
    val changes: StateFlow<Long> = _tick.asStateFlow()

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var passiveListener: LocationListener? = null
    private var stepListener: SensorEventListener? = null
    @Volatile private var lastStepMillis = 0L

    fun availableSignals(): Set<Signal> = buildSet {
        add(Signal.TIME); add(Signal.PROFILE)
        if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)) add(Signal.ACCESSORY)
        if (location.hasPermission()) { add(Signal.LOCATION); add(Signal.WIFI) }
        if (hasUsageAccess()) add(Signal.FOREGROUND_APP)
        if ((Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)) && stepSensor() != null) add(Signal.ACTIVITY)
    }

    /** Starts only the sources that [needed] signals require; stops the rest. */
    @SuppressLint("MissingPermission")
    fun configure(needed: Set<Signal>) {
        val available = availableSignals()
        toggle(Signal.WIFI in needed && Signal.WIFI in available, wifiCallback != null, ::startWifi, ::stopWifi)
        toggle(Signal.LOCATION in needed && Signal.LOCATION in available, passiveListener != null, ::startPassiveLocation, ::stopPassiveLocation)
        toggle(Signal.ACTIVITY in needed && Signal.ACTIVITY in available, stepListener != null, ::startSteps, ::stopSteps)
    }

    suspend fun refreshLocation() {
        location.currentLocation(10_000)?.let { _location.value = it; bump() }
    }

    fun placesContaining(places: List<PlaceEntity>): Set<Long> {
        val loc = _location.value ?: return emptySet()
        // A fix older than 30 minutes is not trusted for "inside a place".
        if (System.currentTimeMillis() - loc.time > 30 * 60_000L) return emptySet()
        return places.filter { p ->
            val out = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, p.latitude, p.longitude, out)
            out[0] <= p.radiusMeters + loc.accuracy.coerceAtMost(100f)
        }.map { it.id }.toSet()
    }

    /** Foreground package from the last minute of usage events. Requires the Usage access setting. */
    fun foregroundPackage(): String? {
        if (!hasUsageAccess()) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 60_000, now)
        var last: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }

    fun currentActivity(): MotionActivity? {
        if (stepListener == null) return null
        return if (System.currentTimeMillis() - lastStepMillis < 60_000) MotionActivity.WALKING else MotionActivity.STILL
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun toggle(want: Boolean, running: Boolean, start: () -> Unit, stop: () -> Unit) {
        if (want && !running) runCatching(start) else if (!want && running) runCatching(stop)
    }

    private fun startWifi() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _wifiSsid.value = (caps.transportInfo as? WifiInfo)?.ssid?.unquote(); bump()
                }
                override fun onLost(network: Network) { _wifiSsid.value = null; bump() }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                @Suppress("DEPRECATION")
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val wm = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                    _wifiSsid.value = wm?.connectionInfo?.ssid?.unquote(); bump()
                }
                override fun onLost(network: Network) { _wifiSsid.value = null; bump() }
            }
        }
        cm.registerNetworkCallback(request, cb)
        wifiCallback = cb
    }

    private fun stopWifi() {
        wifiCallback?.let { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wifiCallback = null; _wifiSsid.value = null
    }

    @SuppressLint("MissingPermission")
    private fun startPassiveLocation() {
        val lm = context.getSystemService(LocationManager::class.java)
        val l = LocationListener { loc -> _location.value = loc; bump() }
        lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 5 * 60_000L, 50f, l, Looper.getMainLooper())
        passiveListener = l
        location.lastKnown()?.let { _location.value = it }
    }

    private fun stopPassiveLocation() {
        passiveListener?.let { context.getSystemService(LocationManager::class.java).removeUpdates(it) }
        passiveListener = null
    }

    private fun stepSensor(): Sensor? = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private fun startSteps() {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = stepSensor() ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val wasWalking = _activity.value == MotionActivity.WALKING
                lastStepMillis = System.currentTimeMillis()
                _activity.value = MotionActivity.WALKING
                if (!wasWalking) bump()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // A large max report latency lets the sensor hub batch steps; the CPU stays asleep.
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        stepListener = l
    }

    private fun stopSteps() {
        stepListener?.let { context.getSystemService(SensorManager::class.java).unregisterListener(it) }
        stepListener = null; _activity.value = null
    }

    private fun bump() { _tick.value = _tick.value + 1 }
    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun String.unquote(): String? = removeSurrounding("\"").takeIf { it.isNotBlank() && it != WifiManagerUnknownSsid }

    companion object { private const val WifiManagerUnknownSsid = "<unknown ssid>" }
}
