package app.auralynx.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auralynx.core.bluetooth.AccessoryState
import app.auralynx.core.data.db.GestureBindingEntity
import app.auralynx.core.data.repo.GestureAction
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.BatteryGauge
import app.auralynx.core.designsystem.DisconnectedState
import app.auralynx.core.designsystem.EstimateLabel
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.SettingSwitch
import app.auralynx.core.designsystem.StatusPill
import app.auralynx.core.designsystem.Tokens
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.ChargingState
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.PressType
import app.auralynx.system.label
import app.auralynx.ui.Route
import app.auralynx.ui.common.label
import app.auralynx.ui.common.note
import app.auralynx.ui.common.title

@Composable
fun ScreenColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.gutter)) {
        content()
        Spacer(Modifier.height(Tokens.xl))
    }
}

@Composable
private fun FeatureGate(a: AccessoryState, feature: Feature, content: @Composable () -> Unit) {
    if (a.capabilities.isOperable(feature)) content() else {
        val l = a.capabilities.support.getValue(feature).label()
        Row { StatusPill("${feature.title()}: ${l.status}", l.kind) }
        l.explanation?.let { InfoNote(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoiseControlScreen(vm: DeviceViewModel, onNavigate: (Route) -> Unit) {
    val a by vm.active.collectAsStateWithLifecycle()
    val acc = a ?: return DisconnectedState({ onNavigate(Route.Discovery) })
    ScreenColumn {
        SectionHeader("Listening mode")
        AuralynxCard {
            if (Feature.NOISE_CONTROL !in acc.model.declaredFeatures) Text("${acc.model.displayName} has no noise-control modes.")
            else FeatureGate(acc, Feature.NOISE_CONTROL) { ModeSelector(acc, vm) }
        }

        if (Feature.ADAPTIVE_AUDIO in acc.model.declaredFeatures) {
            SectionHeader("Adaptive mode strength")
            AuralynxCard {
                FeatureGate(acc, Feature.ADAPTIVE_AUDIO) {
                    var level by remember(acc.aap?.adaptiveNoiseLevel) { mutableFloatStateOf((acc.aap?.adaptiveNoiseLevel ?: 50).toFloat()) }
                    Text("More ambient sound  ←→  Less ambient sound", style = MaterialTheme.typography.bodySmall)
                    Slider(level, { level = it }, valueRange = 0f..100f, steps = 9, onValueChangeFinished = { vm.setAdaptiveLevel(level.toInt()) },
                        modifier = Modifier.semantics { contentDescription = "Adaptive noise level ${level.toInt()}" })
                    InfoNote(acc.aap?.adaptiveNoiseLevel?.let { "Accessory reports $it" } ?: "Not reported yet; applies only in Adaptive mode")
                }
            }
        }

        if (Feature.CONVERSATION_AWARENESS in acc.model.declaredFeatures) {
            SectionHeader("Conversation awareness")
            AuralynxCard {
                FeatureGate(acc, Feature.CONVERSATION_AWARENESS) {
                    SettingSwitch("Lower media when you speak", acc.aap?.conversationAwareness == true, vm::setConversationAwareness,
                        supporting = if (acc.aap?.wearerSpeaking == true) "Speech detected now" else "Handled by the accessory")
                }
            }
        }

        if (acc.model.listeningModes.size >= 2) {
            SectionHeader("Press-and-hold cycles through")
            AuralynxCard {
                FeatureGate(acc, Feature.NOISE_CONTROL) {
                    var chosen by remember(acc.aap?.cycleModes) { mutableStateOf(acc.aap?.cycleModes ?: acc.model.listeningModes) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                        acc.model.listeningModes.sortedBy { it.wire }.forEach { m ->
                            FilterChip(selected = m in chosen, onClick = { chosen = if (m in chosen) chosen - m else chosen + m }, label = { Text(m.label()) })
                        }
                    }
                    InfoNote("Choose at least two modes.")
                    TextButton(onClick = { vm.setCycle(chosen) }, enabled = chosen.size >= 2) { Text("Apply") }
                }
            }
        }

        if (Feature.RENAME in acc.model.declaredFeatures) {
            SectionHeader("Name")
            AuralynxCard {
                FeatureGate(acc, Feature.RENAME) {
                    var name by remember { mutableStateOf(acc.name.orEmpty()) }
                    OutlinedTextField(name, { if (it.encodeToByteArray().size <= 32) name = it }, label = { Text("Accessory name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.rename(name) }, enabled = name.isNotBlank()) { Text("Rename") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GesturesScreen(vm: DeviceViewModel, onNavigate: (Route) -> Unit) {
    val a by vm.active.collectAsStateWithLifecycle()
    val bindings by vm.bindings.collectAsStateWithLifecycle()
    ScreenColumn {
        InfoNote("Auralynx can react only to presses the accessory reports to the phone. Binding a press type makes the accessory forward it instead of handling it itself, so its built-in action stops for that press.")
        val acc = a
        if (acc == null || !acc.capabilities.isOperable(Feature.STEM_PRESS_EVENTS)) {
            AuralynxCard {
                val l = acc?.capabilities?.support?.get(Feature.STEM_PRESS_EVENTS)?.label()
                StatusPill(l?.status ?: "Not connected", l?.kind ?: app.auralynx.core.designsystem.StatusKind.UNAVAILABLE)
                InfoNote(l?.explanation ?: "Press events need a connected accessory with the accessory channel.")
            }
        }
        SectionHeader("Bindings")
        AuralynxCard {
            if (bindings.isEmpty()) Text("No bindings yet.")
            bindings.forEach { b ->
                ListItem(
                    headlineContent = { Text("${b.pressType.lowercase().replaceFirstChar { it.uppercase() }} press · ${b.bud?.lowercase() ?: "either bud"}") },
                    supportingContent = { Text(b.action.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } + (b.argument?.let { " ($it)" } ?: "")) },
                    trailingContent = { IconButton(onClick = { vm.deleteBinding(b.id) }) { Icon(Icons.Outlined.Delete, "Delete binding") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        SectionHeader("Add binding")
        AuralynxCard {
            var press by remember { mutableStateOf(PressType.DOUBLE) }
            var action by remember { mutableStateOf(GestureAction.NEXT_TRACK) }
            var arg by remember { mutableStateOf("") }
            Dropdown("Press", PressType.entries, press, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { press = it }
            Spacer(Modifier.height(Tokens.sm))
            Dropdown("Action", GestureAction.entries, action, { it.name.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() } }) { action = it }
            if (action in setOf(GestureAction.APPLY_AUDIO_PROFILE, GestureAction.TOGGLE_PROFILE, GestureAction.LAUNCH_APP)) {
                OutlinedTextField(arg, { arg = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = Tokens.sm),
                    label = { Text(when (action) { GestureAction.APPLY_AUDIO_PROFILE -> "Audio profile id"; GestureAction.TOGGLE_PROFILE -> "Profile name"; else -> "App package name" }) })
            }
            if (action == GestureAction.LAUNCH_APP || action == GestureAction.START_RECORDING) {
                InfoNote("Android allows this only while Auralynx is on screen.")
            }
            Button(onClick = { vm.saveBinding(GestureBindingEntity(0, press.name, null, action.name, arg.ifBlank { null })) }, modifier = Modifier.padding(top = Tokens.sm)) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(text(selected), {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); expanded = false }) }
        }
    }
}

/** Dedicated AirPods Max view; capabilities are detected per generation, never assumed. */
@Composable
fun AirPodsMaxScreen(vm: DeviceViewModel, onNavigate: (Route) -> Unit) {
    val a by vm.active.collectAsStateWithLifecycle()
    val sessions by vm.listeningSessions.collectAsStateWithLifecycle()
    val acc = a
    ScreenColumn {
        if (acc == null || !acc.model.isAirPodsMax) {
            app.auralynx.core.designsystem.StateMessage(Icons.Outlined.Headphones, "No AirPods Max connected",
                "Connect AirPods Max to see this dashboard.", actionLabel = "Find devices", onAction = { onNavigate(Route.Discovery) })
            return@ScreenColumn
        }
        Text(acc.model.displayName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Tokens.md))
        InfoNote("AirPods Max control over the accessory channel is not yet confirmed by any open-source implementation. Features show \"unverified\" until Auralynx observes them working on your headphones.")
        SectionHeader("Battery")
        AuralynxCard {
            val r = acc.battery[BatteryComponent.SINGLE] ?: acc.battery.values.firstOrNull()
            BatteryGauge("Headphones", r?.level, r?.charging == ChargingState.CHARGING, precisionNote = r?.note(), diameter = androidx.compose.ui.unit.Dp(120f))
        }
        SectionHeader("Listening mode")
        AuralynxCard { FeatureGate(acc, Feature.NOISE_CONTROL) { ModeSelector(acc, vm) } }
        SectionHeader("Listening time (last 7 days)")
        AuralynxCard {
            val now = System.currentTimeMillis()
            val total = sessions.sumOf { (it.endMillis ?: now) - it.startMillis } / 60_000
            val loud = sessions.sumOf { it.highVolumeSeconds } / 60
            Text("$total min listening", style = MaterialTheme.typography.titleLarge)
            Row { EstimateLabel("Volume-based estimate") }
            Text("$loud min with media volume at 80 % or higher", style = MaterialTheme.typography.bodyMedium)
            InfoNote("Exposure is estimated from Android's media volume, not measured sound pressure. Auralynx cannot read decibel levels from the headphones.")
        }
        SectionHeader("Sleep timer")
        SleepTimerCard()
        SectionHeader("Capabilities detected")
        AuralynxCard {
            Feature.entries.filter { acc.capabilities.support[it] !is app.auralynx.core.protocol.devices.FeatureSupport.NotSupportedByModel }.forEach { f ->
                val l = acc.capabilities.support.getValue(f).label()
                app.auralynx.core.designsystem.CapabilityRow(f.title(), l.status, l.kind, l.explanation)
            }
        }
    }
}

@Composable
private fun SleepTimerCard() {
    val vm: SleepTimerViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val endsAt by vm.endsAt.collectAsStateWithLifecycle()
    AuralynxCard {
        if (endsAt != null && endsAt!! > System.currentTimeMillis()) {
            Text("Pausing media at " + app.auralynx.ui.common.formatTime(endsAt))
            TextButton(onClick = vm::cancel) { Text("Cancel") }
        } else {
            Text("Pause media playback after:")
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                listOf(15, 30, 60, 90).forEach { m -> TextButton(onClick = { vm.start(m) }) { Text("$m min") } }
            }
        }
    }
}
