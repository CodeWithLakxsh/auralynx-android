package app.auralynx.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.auralynx.core.bluetooth.system.BluetoothPermissions
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.CapabilityRow
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.StatusKind
import app.auralynx.core.designsystem.Tokens
import kotlinx.coroutines.launch

private data class Page(val title: String, val body: String, val bullets: List<String>)

private val pages = listOf(
    Page("Auralynx", "Beyond Bluetooth. Beyond Boundaries.\n\nA companion for AirPods and Beats on Android.", listOf(
        "Battery, ear detection and noise control",
        "Smart automation, equalizer, recorder, live translation",
        "Open source, private by design",
    )),
    Page("What works on your phone", "Auralynx never needs root, Shizuku or ADB.", listOf(
        "Every Android 8+ phone: battery, charging and ear status from the accessory's Bluetooth advertisements (10 % steps), connection history, alerts, automation, EQ, recorder and translation.",
        "Noise-control switching needs Apple's accessory channel. Android does not yet offer apps a public API for it, so Auralynx shows these controls as unavailable instead of pretending. It checks again after every OS update.",
        "Auralynx never uses hidden system APIs or modifies the Bluetooth stack.",
    )),
    Page("Your data stays here", "No account, no analytics, no ads.", listOf(
        "History and settings are stored only on this phone.",
        "Location and microphone are used only for features you turn on.",
        "Diagnostic reports are created only when you export one.",
    )),
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(Tokens.gutter)) {
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                Text(p.title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Tokens.md))
                Text(p.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Tokens.lg))
                p.bullets.forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Tokens.xs)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${pager.currentPage + 1} / ${pages.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (pager.currentPage < pages.lastIndex) {
                TextButton(onClick = onFinished) { Text("Skip") }
                Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Text("Next") }
            } else Button(onClick = onFinished) { Text("Get started") }
        }
    }
}

@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val essential = BluetoothPermissions.all + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Tokens.gutter)) {
        Text("Permissions", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Tokens.lg).semantics { heading() })
        Text("Auralynx asks only for what the core features need now. Everything else is requested when you turn on the feature that uses it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Tokens.md))
        AuralynxCard {
            @Suppress("UNUSED_EXPRESSION") refresh
            val bt = BluetoothPermissions.hasConnect(context) && BluetoothPermissions.hasScan(context)
            CapabilityRow(if (Build.VERSION.SDK_INT >= 31) "Nearby devices" else "Location (for Bluetooth scanning)", if (bt) "Granted" else "Needed",
                if (bt) StatusKind.LIVE else StatusKind.LIMITED,
                if (Build.VERSION.SDK_INT >= 31) "See your connected accessory and read its status broadcasts. Auralynx declares that it never uses Bluetooth to determine your location."
                else "Android 8–11 require location permission for Bluetooth scanning. Auralynx does not record your location for this.")
            if (Build.VERSION.SDK_INT >= 33) {
                val n = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                CapabilityRow("Notifications", if (n) "Granted" else "Recommended", if (n) StatusKind.LIVE else StatusKind.LIMITED, "Battery status and alerts you enable.")
            }
        }
        InfoNote("Location, microphone, activity and usage access are optional and requested later, only if you use automation, recording, translation or the finder's last-seen location.")
        Spacer(Modifier.height(Tokens.lg))
        Button(onClick = { launcher.launch(essential.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) { Text("Grant permissions") }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}
