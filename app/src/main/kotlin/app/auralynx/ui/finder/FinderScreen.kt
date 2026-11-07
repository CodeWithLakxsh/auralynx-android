package app.auralynx.ui.finder

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.core.bluetooth.ble.BleSighting
import app.auralynx.core.bluetooth.ble.ProximityScanner
import app.auralynx.core.bluetooth.ble.ScanIntensity
import app.auralynx.core.data.repo.DeviceRepository
import app.auralynx.core.data.security.SecretStore
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.StatusKind
import app.auralynx.core.designsystem.StatusPill
import app.auralynx.core.protocol.ble.ProximityCrypto
import app.auralynx.ui.common.formatTime
import app.auralynx.ui.common.relativeTime
import app.auralynx.ui.device.ScreenColumn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class FinderTarget(val sighting: BleSighting, val smoothedRssi: Double, val trend: Int, val verified: Boolean)

@HiltViewModel
class FinderViewModel @Inject constructor(
    scanner: ProximityScanner,
    devices: DeviceRepository,
    private val secrets: SecretStore,
    accessories: AccessoryManager,
) : ViewModel() {
    val known = devices.knownDevices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val connected = accessories.accessories

    /** Exponentially smoothed RSSI per address; trend compares to ~3 s ago. */
    val targets = combine(scanner.proximity(ScanIntensity.LOW_LATENCY), devices.knownDevices) { s, k -> s to k }
        .runningFold(emptyMap<String, FinderTarget>()) { acc, (s, k) ->
            val prev = acc[s.address]
            val smoothed = prev?.let { 0.7 * it.smoothedRssi + 0.3 * s.rssi } ?: s.rssi.toDouble()
            val trend = when { prev == null -> 0; smoothed > prev.smoothedRssi + 1.5 -> 1; smoothed < prev.smoothedRssi - 1.5 -> -1; else -> prev.trend }
            val verified = k.any { d -> secrets.get(SecretStore.irkName(d.address))?.let { ProximityCrypto.resolvesTo(s.address, it) } == true }
            (acc + (s.address to FinderTarget(s, smoothed, trend, verified))).filterValues { System.currentTimeMillis() - it.sighting.timeMillis < 20_000 }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), emptyMap())
}

@Composable
fun FinderScreen(vm: FinderViewModel) {
    val targets by vm.targets.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ScreenColumn {
        InfoNote("Finds accessories by their Bluetooth signal strength. Signal strength changes with walls, bodies and the case, so this shows warmer/colder guidance, not distance. Apple's Find My network is not available to Android apps.")
        SectionHeader("Nearby")
        if (targets.isEmpty()) AuralynxCard { Text("No accessory advertisements received. Open the case lid or take an earbud out to make it broadcast.") }
        targets.values.sortedWith(compareByDescending<FinderTarget> { it.verified }.thenByDescending { it.smoothedRssi }).forEach { t ->
            AuralynxCard {
                Text(t.sighting.model?.displayName ?: "Apple accessory", style = MaterialTheme.typography.titleMedium)
                StatusPill(if (t.verified) "Your accessory (key verified)" else "Unverified: may not be yours", if (t.verified) StatusKind.LIVE else StatusKind.LIMITED)
                val strength = ((t.smoothedRssi + 100) / 60).coerceIn(0.0, 1.0).toFloat()
                val anim by animateFloatAsState(strength, label = "rssi")
                LinearProgressIndicator(progress = { anim }, modifier = Modifier.fillMaxWidth().height(10.dp)
                    .semantics { contentDescription = "Signal ${t.smoothedRssi.toInt()} dBm" })
                Text("${t.smoothedRssi.toInt()} dBm · " + when (t.trend) { 1 -> "getting warmer"; -1 -> "getting colder"; else -> "steady" }, style = MaterialTheme.typography.bodyLarge)
                Text("Updated ${relativeTime(t.sighting.timeMillis)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionHeader("Last known")
        known.forEach { d ->
            AuralynxCard {
                Text(d.name ?: "Accessory", style = MaterialTheme.typography.titleMedium)
                Text("Last connected: ${formatTime(d.lastConnectedMillis)}")
                Text("Last disconnected: ${formatTime(d.lastDisconnectedMillis)}")
                if (d.lastLatitude != null && d.lastLongitude != null) {
                    Text("Phone location at disconnect: ±${d.lastLocationAccuracyMeters?.toInt() ?: "?"} m, ${formatTime(d.lastLocationMillis)}")
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, ("geo:${d.lastLatitude},${d.lastLongitude}?q=${d.lastLatitude},${d.lastLongitude}(Last seen)").toUri())) }
                    }) { Text("Open in maps") }
                } else InfoNote("Location at disconnect is off. Enable it in Settings → Privacy (needs location permission).")
            }
        }
    }
}

