package app.auralynx.ui.device

import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auralynx.core.common.Diag
import app.auralynx.core.common.Redaction
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.StatusKind
import app.auralynx.core.designsystem.StatusPill
import app.auralynx.ui.Route
import app.auralynx.ui.common.relativeTime
import java.util.regex.Pattern

@Composable
fun DiscoveryScreen(vm: DeviceViewModel, onNavigate: (Route) -> Unit) {
    val connected by vm.all.collectAsStateWithLifecycle()
    val bonded by vm.bonded.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshBonded() }
    val linkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

    ScreenColumn {
        SectionHeader("Connected")
        AuralynxCard {
            if (connected.isEmpty()) Text("No supported accessory is connected. Pair and connect it in Android's Bluetooth settings first.")
            connected.forEach { a ->
                ListItem(
                    headlineContent = { Text(a.name ?: a.model.displayName) },
                    supportingContent = { Text(a.model.displayName) },
                    trailingContent = { StatusPill("Connected", StatusKind.LIVE) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { vm.select(a.address); onNavigate(Route.Home) },
                )
            }
        }

        SectionHeader("Paired")
        AuralynxCard {
            if (bonded.isEmpty()) Text("No paired Apple or Beats accessories found (or Nearby devices permission not granted).")
            bonded.forEach { b ->
                val isKnown = known.any { it.address == b.address }
                ListItem(
                    headlineContent = { Text(b.name ?: "Accessory") },
                    supportingContent = { Text(Redaction.address(b.address) + if (isKnown) " · seen by Auralynx" else "") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        SectionHeader("Link for background monitoring")
        AuralynxCard {
            InfoNote("Linking uses Android's companion device setup. It lets Auralynx start monitoring when your accessory connects, without keeping a service running. You choose the device in a system dialog.")
            Button(onClick = { startCompanionLink(context) { linkLauncher.launch(IntentSenderRequest.Builder(it).build()) } }) { Text("Link accessory") }
        }

        SectionHeader("Nearby advertisements")
        AuralynxCard {
            InfoNote("Apple accessories broadcast status over Bluetooth Low Energy with changing addresses. Nearby entries may belong to other people.")
            if (nearby.isEmpty()) Text("Listening… (needs the Nearby devices permission)")
            nearby.values.sortedByDescending { it.rssi }.forEach { s ->
                ListItem(
                    headlineContent = { Text(s.model?.displayName ?: "Apple accessory (model 0x%04X)".format(s.status.modelCode)) },
                    supportingContent = { Text("Signal ${s.rssi} dBm · ${relativeTime(s.timeMillis)}") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

/** Opens the system companion-device chooser filtered to Apple/Beats audio accessories. */
fun startCompanionLink(context: Context, launch: (IntentSender) -> Unit) {
    val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
    val filter = BluetoothDeviceFilter.Builder().setNamePattern(Pattern.compile("(?i).*(airpods|beats|powerbeats).*")).build()
    val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
    val callback = object : CompanionDeviceManager.Callback() {
        @Deprecated("Deprecated in API 33") override fun onDeviceFound(chooserLauncher: IntentSender) = launch(chooserLauncher)
        override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
        override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
            if (Build.VERSION.SDK_INT >= 33) {
                associationInfo.deviceMacAddress?.toString()?.let { mac ->
                    runCatching { cdm.startObservingDevicePresence(mac.uppercase()) }.onFailure { Diag.w("Companion", it) { "Presence observation failed" } }
                }
            }
        }
        override fun onFailure(error: CharSequence?) { Diag.w("Companion") { "Association failed: $error" } }
    }
    @Suppress("DEPRECATION")
    cdm.associate(request, callback, null)
}
