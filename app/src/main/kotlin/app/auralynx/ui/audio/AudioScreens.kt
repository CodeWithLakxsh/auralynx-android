package app.auralynx.ui.audio

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auralynx.core.audio.RecordingInput
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.BarsVisualizer
import app.auralynx.core.designsystem.ExperimentalBanner
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.LineChart
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.SettingSwitch
import app.auralynx.core.designsystem.StateMessage
import app.auralynx.core.designsystem.Tokens
import app.auralynx.core.dsp.GraphicEqualizer
import app.auralynx.core.dsp.SpatialMode
import app.auralynx.ui.Route
import app.auralynx.ui.common.formatTime
import app.auralynx.ui.device.NavRow
import app.auralynx.ui.device.ScreenColumn

@Composable
fun AudioHubScreen(onNavigate: (Route) -> Unit) {
    ScreenColumn {
        Text("Audio", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Tokens.lg))
        AuralynxCard {
            NavRow("Equalizer", "Profiles for Auralynx playback and for players that share their audio session") { onNavigate(Route.Equalizer) }
            NavRow("Spatial audio", "Experimental binaural rendering inside Auralynx") { onNavigate(Route.Spatial) }
            NavRow("Recorder", "Record, clean up and export voice notes") { onNavigate(Route.Recorder) }
            NavRow("Live translation", "Speech → translation → speech in your headphones") { onNavigate(Route.Translation) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(vm: EqualizerViewModel) {
    val all by vm.all.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val sessionOn by vm.sessionEqEnabled.collectAsStateWithLifecycle()
    val sessions by vm.externalSessions.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    ScreenColumn {
        SectionHeader("Profiles")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
            all.forEach { p -> FilterChip(selected == p.id, { vm.select(p) }, { Text(p.name) }) }
        }
        SectionHeader("Response")
        AuralynxCard {
            LineChart(listOf(draft.name to vm.curve()), "Equalizer response curve from 20 Hz to 20 kHz, range ±15 dB")
            InfoNote("Curve computed from the actual filters Auralynx applies (±15 dB scale).")
        }
        SectionHeader("Bands")
        AuralynxCard {
            GraphicEqualizer.BANDS.forEachIndexed { i, f ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
                    Slider(draft.gainsDb[i].toFloat(), { vm.setBand(i, it.toDouble()) }, valueRange = -12f..12f, modifier = Modifier.weight(1f)
                        .semantics { contentDescription = "${f.toInt()} hertz, ${"%.1f".format(draft.gainsDb[i])} decibels" })
                    Text("%+.0f".format(draft.gainsDb[i]), Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Pre", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
                Slider(draft.preampDb.toFloat(), { vm.setPreamp(it.toDouble()) }, valueRange = -12f..6f, modifier = Modifier.weight(1f))
                Text("%+.0f".format(draft.preampDb), Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
            }
            OutlinedTextField(name, { name = it }, label = { Text("Save as") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                Button(onClick = { vm.saveAs(name.ifBlank { "Custom" }) }) { Text("Save & use") }
                OutlinedButton(onClick = vm::activate) { Text("Use selected") }
                if (all.firstOrNull { it.id == selected }?.builtIn == null && selected != null) TextButton(onClick = vm::deleteSelected) { Text("Delete") }
            }
        }
        SectionHeader("Where it applies")
        AuralynxCard {
            Text("Always: audio played inside Auralynx (spatial audio player, recordings).", style = MaterialTheme.typography.bodyMedium)
            SettingSwitch("Players that share their session", sessionOn, vm::setSessionEq,
                supporting = "Android lets equalizers attach only to apps that announce their audio session, and only while Auralynx is running (for example while your accessory is connected). This is not a system-wide equalizer.")
            if (sessions.isEmpty()) InfoNote("No player has announced a session yet.")
            sessions.forEach { s -> Text("• ${s.packageName ?: "Unknown app"} (session ${s.sessionId}, ${s.engine})", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun SpatialScreen(vm: SpatialViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.open(it, it.lastPathSegment) } }
    ScreenColumn {
        ExperimentalBanner("Experimental. Auralynx renders stereo through a generic spherical-head model inside its own player. It is not Apple's Personalized Spatial Audio and does not process other apps.")
        SectionHeader("Player")
        AuralynxCard {
            Text(state.title ?: "No file loaded", style = MaterialTheme.typography.titleMedium)
            if (state.durationMillis > 0) Text("${state.positionMillis / 1000}s / ${state.durationMillis / 1000}s", style = MaterialTheme.typography.bodySmall)
            BarsVisualizer(state.spectrum)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Open audio file") }
                if (state.uri != null) OutlinedButton(onClick = vm::togglePause) { Text(if (state.playing) "Pause" else "Play") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!vm.bluetoothOutput) InfoNote("Headphone output not detected. Spatial rendering is designed for headphones.")
        }
        SectionHeader("Mode")
        AuralynxCard {
            SpatialMode.entries.forEach { m ->
                ListItem(headlineContent = { Text(m.displayName) }, supportingContent = { Text(describe(m)) },
                    trailingContent = { androidx.compose.material3.RadioButton(mode == m, { vm.setMode(m) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
        SectionHeader("Measured performance")
        AuralynxCard {
            Text("DSP algorithmic latency: ${"%.2f".format(state.dspLatencyMillis)} ms")
            Text("DSP load: ${"%.1f".format(state.dspLoad * 100)} % of real time (measured)")
            Text("Output buffer: " + (state.outputBufferMillis?.let { "%.1f ms".format(it) } ?: "—"))
            InfoNote("Bluetooth codec latency is added by Android and the headphones and is not included.")
        }
        SectionHeader("System spatial audio")
        AuralynxCard {
            val p = vm.platform
            if (!p.apiAvailable) Text("Android's Spatializer API needs Android 12L or newer.")
            else {
                Text("Spatializer available: ${p.available}")
                Text("Enabled by user: ${p.enabled}")
                Text("Head tracker available: ${p.headTrackerAvailable}")
            }
            InfoNote("Reported by android.media.Spatializer. System spatial audio is controlled by Android and your phone maker, not by Auralynx.")
        }
    }
}

private fun describe(m: SpatialMode) = when (m) {
    SpatialMode.OFF -> "Unprocessed stereo"
    SpatialMode.CROSSFEED -> "Blends a little of each channel into the other, like speakers; reduces fatigue"
    SpatialMode.VIRTUAL_SPEAKERS -> "Places left/right as speakers at ±30° using head-shadow and time-delay cues"
    SpatialMode.VIRTUAL_ROOM -> "Virtual speakers plus early room reflections"
}

@Composable
fun RecorderScreen(vm: RecorderViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by remember { mutableStateOf(RecordingInput.PHONE) }
    var platformNs by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.start(input, platformNs) }
    ScreenColumn {
        SectionHeader("Record")
        AuralynxCard {
            if (state.recording) {
                Text("Recording · ${state.elapsedMillis / 1000}s", style = MaterialTheme.typography.titleLarge)
                Text("Level ${"%.0f".format(state.levelDb)} dBFS", style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::stop) { Text("Stop") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                    FilterChip(input == RecordingInput.PHONE, { input = RecordingInput.PHONE }, { Text("Phone mic") })
                    FilterChip(input == RecordingInput.BLUETOOTH_HEADSET, { input = RecordingInput.BLUETOOTH_HEADSET }, { Text("Headset mic") }, enabled = vm.bluetoothMicAvailable)
                }
                if (input == RecordingInput.BLUETOOTH_HEADSET) InfoNote("Uses the standard hands-free profile: 16 kHz narrow-band, and music quality drops while recording.")
                SettingSwitch("Android noise suppressor while recording", platformNs, { platformNs = it })
                Button(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Icon(Icons.Outlined.Mic, null); Text(" Start recording") }
            }
            InfoNote("Records only Auralynx's own microphone input, stored privately on this device. Calls and other apps' audio cannot be captured.")
        }
        busy?.let { InfoNote(it) }
        SectionHeader("Recordings")
        if (recordings.isEmpty()) StateMessage(Icons.Outlined.Mic, "No recordings", "Your recordings appear here.")
        recordings.forEach { r ->
            AuralynxCard {
                Text(r.title, style = MaterialTheme.typography.titleMedium)
                Text("${formatTime(r.createdAtMillis)} · ${r.durationMillis / 1000}s · ${r.sampleRate / 1000} kHz", style = MaterialTheme.typography.bodySmall)
                Row {
                    IconButton(onClick = { vm.play(r) }) { Icon(Icons.Outlined.PlayArrow, "Play") }
                    IconButton(onClick = { vm.exportFile(r, aac = true) { f -> share(context, f, "audio/mp4") } }) { Icon(Icons.Outlined.Share, "Share as AAC") }
                    IconButton(onClick = { vm.delete(r) }) { Icon(Icons.Outlined.Delete, "Delete recording") }
                }
                if (!r.noiseReduced && !r.voiceEnhanced) Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                    TextButton(onClick = { vm.process(r, noise = true, voice = false) }) { Text("Reduce noise") }
                    TextButton(onClick = { vm.process(r, noise = true, voice = true) }) { Text("Enhance voice") }
                    TextButton(onClick = { vm.exportFile(r, aac = false) { f -> share(context, f, "audio/wav") } }) { Text("WAV") }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(Tokens.xs))
        }
    }
}

fun share(context: android.content.Context, file: java.io.File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
}

