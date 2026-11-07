package app.auralynx.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.auralynx.core.audio.SessionEffectsController
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.core.common.Diag
import app.auralynx.system.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service of type `connectedDevice`, running only while a supported accessory is
 * connected. It keeps the accessory channel and session effects alive and shows battery status.
 * It stops itself as soon as no accessory is connected.
 */
@AndroidEntryPoint
class MonitorService : LifecycleService() {
    @Inject lateinit var notifications: Notifications
    @Inject lateinit var accessories: AccessoryManager
    @Inject lateinit var coordinator: AccessoryCoordinator
    @Inject lateinit var effects: SessionEffectsController

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching {
            ServiceCompat.startForeground(this, Notifications.ID_MONITOR, notifications.monitor(accessories.accessories.value.firstOrNull()), type)
        }.onFailure { Diag.w(TAG, it) { "Could not enter foreground" }; stopSelf(); return START_NOT_STICKY }
        coordinator.start()
        lifecycleScope.launch {
            accessories.accessories.collect { if (it.isEmpty()) stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        effects.releaseAll()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitorService"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            } catch (e: Exception) {
                // Android 12+ blocks background starts unless an exemption applies (e.g. a linked
                // companion device). Monitoring then resumes the next time Auralynx is opened.
                if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                    Diag.w(TAG) { "Background start not allowed; link the accessory as a companion device to enable it" }
                } else {
                    Diag.w(TAG, e) { "Monitor start failed" }
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
