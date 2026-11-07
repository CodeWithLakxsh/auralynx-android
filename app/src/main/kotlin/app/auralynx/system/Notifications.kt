package app.auralynx.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.auralynx.MainActivity
import app.auralynx.R
import app.auralynx.core.bluetooth.AccessoryState
import app.auralynx.core.protocol.model.BatteryComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifications @Inject constructor(@ApplicationContext private val context: Context) {

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_MONITOR, "Accessory status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Auralynx monitors a connected accessory"; setShowBadge(false)
            },
            NotificationChannel(CH_BATTERY, "Battery alerts", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Low-battery and charging alerts" },
            NotificationChannel(CH_SECURITY, "Connection alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Unexpected disconnection alerts" },
            NotificationChannel(CH_RECORDER, "Recorder", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while recording audio" },
        ))
    }

    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun monitor(state: AccessoryState?): android.app.Notification {
        val title = state?.name ?: "Auralynx"
        val text = state?.let { summary(it) } ?: "Waiting for a supported accessory"
        return NotificationCompat.Builder(context, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_auralynx)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun recorder(): android.app.Notification = NotificationCompat.Builder(context, CH_RECORDER)
        .setSmallIcon(R.drawable.ic_stat_auralynx)
        .setContentTitle("Recording")
        .setContentText("Auralynx is recording from the microphone")
        .setOngoing(true).setSilent(true).setContentIntent(openApp())
        .build()

    fun alert(channel: String, id: Int, title: String, text: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_auralynx)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(openApp())
            .build()
        @Suppress("MissingPermission") // checked by canPost()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun updateMonitor(state: AccessoryState?) {
        if (!canPost()) return
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(ID_MONITOR, monitor(state))
    }

    companion object {
        const val CH_MONITOR = "monitor"
        const val CH_BATTERY = "battery"
        const val CH_SECURITY = "security"
        const val CH_RECORDER = "recorder"
        const val ID_MONITOR = 1
        const val ID_RECORDER = 2
        const val ID_LOW_BATTERY = 10
        const val ID_CHARGED = 11
        const val ID_DISCONNECT = 12

        fun summary(s: AccessoryState): String {
            val b = s.battery
            val parts = buildList {
                b[BatteryComponent.LEFT]?.let { add("L ${it.level}%") }
                b[BatteryComponent.RIGHT]?.let { add("R ${it.level}%") }
                b[BatteryComponent.CASE]?.let { add("Case ${it.level}%") }
                b[BatteryComponent.SINGLE]?.let { add("${it.level}%") }
            }
            val mode = s.aap?.listeningMode?.let { " · " + it.label() } ?: ""
            return (if (parts.isEmpty()) "Connected" else parts.joinToString("  ")) + mode
        }
    }
}

fun app.auralynx.core.protocol.model.ListeningMode.label(): String = when (this) {
    app.auralynx.core.protocol.model.ListeningMode.OFF -> "Off"
    app.auralynx.core.protocol.model.ListeningMode.NOISE_CANCELLATION -> "Noise Cancellation"
    app.auralynx.core.protocol.model.ListeningMode.TRANSPARENCY -> "Transparency"
    app.auralynx.core.protocol.model.ListeningMode.ADAPTIVE -> "Adaptive"
}
