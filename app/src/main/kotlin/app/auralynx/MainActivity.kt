package app.auralynx

import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.ui.AuralynxRoot
import app.auralynx.ui.Route
import app.auralynx.ui.routeForDeepLink
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var accessories: AccessoryManager
    private val deepLink = mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLink.value = routeForDeepLink(intent?.data?.host)
        applyDebugExtras(intent)
        publishShortcuts()
        setContent { AuralynxRoot(deepLink.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink.value = routeForDeepLink(intent.data?.host)
        applyDebugExtras(intent)
    }

    /** Debug builds only: `adb shell am start -n <pkg>/app.auralynx.MainActivity --ez bletrace true`. */
    private fun applyDebugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent?.hasExtra("bletrace") != true) return
        accessories.traceAllSightings = intent.getBooleanExtra("bletrace", false)
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been granted in system settings; re-attach monitors.
        accessories.start()
    }

    /** Dynamic launcher shortcuts (package-name independent, so debug and release both work). */
    private fun publishShortcuts() {
        fun shortcut(id: String, label: String, icon: Int) = ShortcutInfoCompat.Builder(this, id)
            .setShortLabel(label)
            .setIcon(IconCompat.createWithResource(this, icon))
            .setIntent(Intent(Intent.ACTION_VIEW, "auralynx://$id".toUri(), this, MainActivity::class.java))
            .build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(this, listOf(
                shortcut("battery", getString(R.string.shortcut_battery), R.drawable.ic_stat_auralynx),
                shortcut("noise", getString(R.string.shortcut_noise), R.drawable.ic_tile_noise),
                shortcut("automation", getString(R.string.shortcut_automation), R.drawable.ic_tile_automation),
                shortcut("finder", getString(R.string.shortcut_finder), R.drawable.ic_stat_auralynx),
            ))
        }
    }
}
