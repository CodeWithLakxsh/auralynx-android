package app.auralynx.ui.experimental

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.auralynx.core.audio.LocalAudioPlayer
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.ExperimentalBanner
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.SettingSwitch
import app.auralynx.core.designsystem.Tokens
import app.auralynx.core.protocol.aap.AapEvent
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.experimental.research.ChannelExperiment
import app.auralynx.experimental.research.ContinuityExplorer
import app.auralynx.experimental.research.HeadTrackingInterpreter
import app.auralynx.ui.common.relativeTime
import app.auralynx.ui.device.ScreenColumn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ExperimentalViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val settingsRepo: SettingsRepository,
    private val accessories: AccessoryManager,
    private val experiment: ChannelExperiment,
    private val explorer: ContinuityExplorer,
    private val player: LocalAudioPlayer,
) : ViewModel() {
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val active = accessories.accessories.map { it.firstOrNull() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val environment = experiment.environment()
    val report = MutableStateFlow<ChannelExperiment.Report?>(null)
    val running = MutableStateFlow(false)
    val head = MutableStateFlow<AapEvent.HeadTracking?>(null)
    val yaw = MutableStateFlow<Double?>(null)
    val interpreter = HeadTrackingInterpreter()
    val observations = MutableStateFlow<Map<String, ContinuityExplorer.Observation>>(emptyMap())
    private var scanJob: Job? = null
    private var headJob: Job? = null
    val driveSpatializer = MutableStateFlow(false)

    fun setExperimental(on: Boolean) = viewModelScope.launch { settingsRepo.update { it.copy(experimentalEnabled = on) } }

    fun runExperiment(testAdaptive: Boolean) = viewModelScope.launch {
        val a = active.value ?: return@launch
        running.value = true
        // The experiment opens its own channel; release the normal session first.
        accessories.updateConfig { it.copy(channelEnabled = false) }
        report.value = runCatching { experiment.run(a.address, testAdaptive) }.getOrNull()
        val channelSetting = settingsRepo.current().accessoryChannelEnabled
        accessories.updateConfig { it.copy(channelEnabled = channelSetting) }
        running.value = false
    }

    fun saveReport(uri: Uri) = viewModelScope.launch {
        val json = report.value?.toJson() ?: return@launch
        withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } } }
    }

    fun startScan() { if (scanJob == null) scanJob = viewModelScope.launch { explorer.observe().collect { observations.value = it } } }
    fun stopScan() { scanJob?.cancel(); scanJob = null }

    fun setHeadTracking(on: Boolean) = viewModelScope.launch {
        val a = active.value ?: return@launch
        accessories.setHeadTracking(a.address, on)
        headJob?.cancel()
        if (on) headJob = viewModelScope.launch {
            accessories.events.map { it.event }.filterIsInstance<AapEvent.HeadTracking>().collect { s ->
                head.value = s
                val y = interpreter.yawDegrees(s)
                yaw.value = y
                if (driveSpatializer.value && y != null) player.setHeadYaw(y)
            }
        } else player.setHeadYaw(0.0)
    }

    override fun onCleared() { stopScan(); headJob?.cancel(); player.setHeadYaw(0.0) }
}

@Composable
fun ExperimentalScreen(vm: ExperimentalViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val a by vm.active.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val head by vm.head.collectAsStateWithLifecycle()
    val yaw by vm.yaw.collectAsStateWithLifecycle()
    val obs by vm.observations.collectAsStateWithLifecycle()
    val drive by vm.driveSpatializer.collectAsStateWithLifecycle()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::saveReport) }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }
    ScreenColumn {
        ExperimentalBanner("Research features. They may not work on your phone or accessory, and nothing here is presented as a supported feature until it has been validated on real hardware.")
        AuralynxCard { SettingSwitch("Enable experimental features", s?.experimentalEnabled == true, vm::setExperimental) }

        SectionHeader("Rootless accessory-channel experiment")
        AuralynxCard {
            vm.environment.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
            InfoNote("Tests whether this phone can open Apple's accessory channel with public APIs, whether the accessory answers, and (optionally) whether Adaptive mode is accepted. Your previous listening mode is restored.")
            var adaptive by remember { mutableStateOf(false) }
            SettingSwitch("Also test Adaptive mode", adaptive, { adaptive = it })
            Button(enabled = a != null && !running, onClick = { vm.runExperiment(adaptive) }) { Text(if (running) "Running…" else "Run experiment") }
            if (a == null) InfoNote("Connect an accessory first.")
            report?.let { r ->
                Text("H1 channel opened: ${r.h1ChannelOpened}", style = MaterialTheme.typography.bodyMedium)
                Text("H2 accessory responded: ${r.h2AccessoryResponded}")
                Text("H3 adaptive accepted: ${r.h3AdaptiveAccepted}")
                Text("Packets: ${r.packetsReceived}; opcodes: ${r.firstOpcodes.joinToString()}", style = MaterialTheme.typography.bodySmall)
                r.steps.forEach { st -> Text("• ${st.name}: ${st.outcome} (${st.millis} ms)", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { saver.launch("auralynx-channel-experiment.json") }) { Text("Save JSON report") }
            }
        }

        SectionHeader("Head tracking")
        AuralynxCard {
            val ok = a?.capabilities?.isOperable(Feature.HEAD_TRACKING) == true
            if (!ok) InfoNote("Needs experimental features, a connected accessory channel and a model with head-tracking sensors.")
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                Button(enabled = ok, onClick = { vm.setHeadTracking(true) }) { Text("Start") }
                OutlinedButton(enabled = ok, onClick = { vm.setHeadTracking(false) }) { Text("Stop") }
            }
            head?.let { h ->
                Text("Raw: ${h.orientation1}, ${h.orientation2}, ${h.orientation3} · accel ${h.horizontalAcceleration}, ${h.verticalAcceleration}", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { vm.interpreter.setForward(h) }) { Text("Set forward") }
                    TextButton(onClick = { vm.interpreter.setNinetyDegrees(h) }) { Text("I turned 90° right") }
                }
                Text(yaw?.let { "Relative yaw ≈ ${it.toInt()}° (uncalibrated estimate)" } ?: "Calibrate to see a relative angle")
                SettingSwitch("Rotate the spatial-audio stage with my head", drive, { vm.driveSpatializer.value = it })
            }
        }

        SectionHeader("Apple ecosystem observer (Apple Watch research)")
        AuralynxCard {
            InfoNote("Passively lists Apple Continuity advertisements nearby and decodes their message types. It never transmits or pairs. Pairing or activating an Apple Watch without an iPhone is not possible for an Android app, and Auralynx does not attempt it.")
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                Button(onClick = vm::startScan) { Text("Observe") }
                OutlinedButton(onClick = vm::stopScan) { Text("Stop") }
            }
            obs.values.sortedByDescending { it.lastRssi }.take(15).forEach { o ->
                Text("${o.lastRssi} dBm · ${relativeTime(o.lastSeenMillis)} · " + o.messageTypes.keys.joinToString(), style = MaterialTheme.typography.bodySmall)
                o.nearbyInfo?.let { n -> Text("   Nearby Info: ${n.actionDescription}" + if (n.possiblyWatchOnWrist) " — possibly a watch" else "", style = MaterialTheme.typography.bodySmall) }
            }
        }

        SectionHeader("Not available")
        AuralynxCard {
            Text("• Apple Watch pairing, notifications, health or calls: requires Apple's pairing protocol and an Apple Account-bound activation. Not implemented.", style = MaterialTheme.typography.bodySmall)
            Text("• Loud sound reduction, hearing assistance, transparency customisation: the accessory enables these only for phones that present Apple's Device ID, which needs root.", style = MaterialTheme.typography.bodySmall)
            Text("• High-quality AirPods microphone: research only; Android has no public path for it.", style = MaterialTheme.typography.bodySmall)
            Text("• Heart-rate data: payload format not yet documented.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

