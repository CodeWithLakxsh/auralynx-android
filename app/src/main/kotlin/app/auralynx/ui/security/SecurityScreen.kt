package app.auralynx.ui.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.auralynx.BuildConfig
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.core.common.Redaction
import app.auralynx.core.data.repo.DataMaintenance
import app.auralynx.core.data.repo.DeviceRepository
import app.auralynx.core.data.repo.DiagnosticsRepository
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.CapabilityRow
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.SettingSwitch
import app.auralynx.core.designsystem.StatusKind
import app.auralynx.ui.common.formatTime
import app.auralynx.ui.device.ScreenColumn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    devices: DeviceRepository,
    private val diagnostics: DiagnosticsRepository,
    private val maintenance: DataMaintenance,
    private val settingsRepo: SettingsRepository,
    private val accessories: AccessoryManager,
) : ViewModel() {
    val events = devices.recentEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val known = devices.knownDevices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs = diagnostics.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(t: (app.auralynx.core.data.settings.AppSettings) -> app.auralynx.core.data.settings.AppSettings) = viewModelScope.launch { settingsRepo.update(t) }

    /** Writes a redacted report to a document the user picked. Nothing is uploaded. */
    fun export(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val a = accessories.accessories.value.firstOrNull()
        val text = diagnostics.buildExport(mapOf(
            "App version" to BuildConfig.VERSION_NAME,
            "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Accessory" to (a?.let { "${it.model.displayName}, channel=${it.channel::class.simpleName}" } ?: "none connected"),
        ))
        val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false) }
        onDone(ok)
    }

    fun clearLogs() = viewModelScope.launch { diagnostics.clear() }
    fun deleteEverything(onDone: () -> Unit) = viewModelScope.launch { maintenance.deleteEverything(); onDone() }

    fun permissions(): List<Triple<String, Boolean, String>> = buildList {
        fun g(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 31) {
            add(Triple("Nearby devices: connect", g(Manifest.permission.BLUETOOTH_CONNECT), "See connected accessories and open the accessory channel"))
            add(Triple("Nearby devices: scan", g(Manifest.permission.BLUETOOTH_SCAN), "Read battery from Bluetooth advertisements (no location)"))
        }
        if (Build.VERSION.SDK_INT >= 33) add(Triple("Notifications", g(Manifest.permission.POST_NOTIFICATIONS), "Status and alerts"))
        add(Triple("Location", g(Manifest.permission.ACCESS_FINE_LOCATION) || g(Manifest.permission.ACCESS_COARSE_LOCATION), "Only for automation places, Wi-Fi names and last-seen location"))
        add(Triple("Microphone", g(Manifest.permission.RECORD_AUDIO), "Only for the recorder and translation"))
    }
}

@Composable
fun SecurityScreen(vm: SecurityViewModel) {
    val events by vm.events.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { vm.export(it) { ok -> message = if (ok) "Diagnostic report saved." else "Could not save the report." } }
    }
    val bgLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    ScreenColumn {
        SectionHeader("Permissions")
        AuralynxCard {
            vm.permissions().forEach { (name, granted, why) -> CapabilityRow(name, if (granted) "Granted" else "Not granted", if (granted) StatusKind.LIVE else StatusKind.UNAVAILABLE, why) }
        }
        SectionHeader("Alerts & location")
        AuralynxCard {
            val st = s ?: return@AuralynxCard
            SettingSwitch("Unexpected disconnection alert", st.disconnectAlertsEnabled, { v -> vm.update { it.copy(disconnectAlertsEnabled = v) } },
                supporting = "Notifies you if the accessory disconnects while it was being worn")
            SettingSwitch("Remember phone location at disconnect", st.locationOnDisconnectEnabled, { v ->
                vm.update { it.copy(locationOnDisconnectEnabled = v) }
                if (v) bgLocation.launch(if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }, supporting = "Stored only on this device. Works in the background only if you allow location \"all the time\" in Android settings.")
        }
        SectionHeader("Known accessories")
        AuralynxCard {
            if (known.isEmpty()) Text("None yet.")
            known.forEach { d -> Text("${d.name ?: "Accessory"} · ${Redaction.address(d.address)} · model ${d.modelNumber ?: d.model ?: "unknown"} · firmware ${d.firmware ?: "unknown"}", style = MaterialTheme.typography.bodySmall) }
        }
        SectionHeader("Connection history")
        AuralynxCard {
            if (events.isEmpty()) Text("No events recorded yet.")
            events.take(30).forEach { e -> Text("${formatTime(e.timestampMillis)} · ${e.deviceName ?: Redaction.address(e.deviceAddress)} · ${e.type.lowercase().replace('_', ' ')}" + (e.detail?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall) }
        }
        SectionHeader("Diagnostics")
        AuralynxCard {
            val st = s
            SettingSwitch("Keep a diagnostic log", st?.diagnosticsLoggingEnabled == true, { v -> vm.update { it.copy(diagnosticsLoggingEnabled = v) } },
                supporting = "Local only, identifiers redacted. Never sent automatically.")
            Text("${logs.size} recent entries", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { exporter.launch("auralynx-diagnostics.txt") }) { Text("Export report…") }
            TextButton(onClick = vm::clearLogs) { Text("Clear log") }
            message?.let { InfoNote(it) }
        }
        SectionHeader("Your data")
        AuralynxCard {
            InfoNote("Auralynx stores data only on this device. It has no analytics and no account.")
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete all Auralynx data") }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete all data?") },
        text = { Text("This removes battery history, rules, recordings, translation history, logs, stored accessory keys and settings. It cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteEverything { message = "All data deleted." } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
