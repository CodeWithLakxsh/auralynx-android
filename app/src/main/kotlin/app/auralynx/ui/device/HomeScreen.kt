package app.auralynx.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auralynx.core.bluetooth.AccessoryState
import app.auralynx.core.bluetooth.ChannelStatus
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.BatteryGauge
import app.auralynx.core.designsystem.DisconnectedState
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.LinkButton
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.StateMessage
import app.auralynx.core.designsystem.StatusPill
import app.auralynx.core.designsystem.Tokens
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.devices.FormFactor
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.ChargingState
import app.auralynx.system.label
import app.auralynx.ui.common.label
import app.auralynx.ui.common.note
import app.auralynx.ui.common.title

@Composable
fun HomeScreen(vm: DeviceViewModel, onNavigate: (app.auralynx.ui.Route) -> Unit) {
    val active by vm.active.collectAsStateWithLifecycle()
    val adapterOn by vm.adapterEnabled.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
      Column(Modifier.widthIn(max = Tokens.maxContentWidth).fillMaxWidth().padding(horizontal = Tokens.gutter)) {
        Text("Auralynx", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Tokens.lg))
        Spacer(Modifier.height(Tokens.sm))
        when {
            !vm.hasBluetooth -> StateMessage(Icons.Outlined.BluetoothDisabled, "Bluetooth not available", "This device has no Bluetooth adapter.")
            !adapterOn -> StateMessage(Icons.Outlined.BluetoothDisabled, "Bluetooth is off", "Turn on Bluetooth to see your accessory.")
            active == null -> DisconnectedState(onFindDevices = { onNavigate(app.auralynx.ui.Route.Discovery) })
            else -> Dashboard(active!!, vm, onNavigate)
        }
        Spacer(Modifier.height(Tokens.xl))
      }
    }
}

@Composable
private fun Dashboard(a: AccessoryState, vm: DeviceViewModel, onNavigate: (app.auralynx.ui.Route) -> Unit) {
    AuralynxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Headphones, null)
            Column(Modifier.padding(start = Tokens.md).weight(1f)) {
                Text(a.name ?: a.model.displayName, style = MaterialTheme.typography.titleLarge)
                Text("${a.model.displayName} · ${a.modelEvidence.label()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Tokens.md))
        val ch = a.channel.label()
        StatusPill(ch.status, ch.kind)
        ch.explanation?.let { InfoNote(it) }
        if (a.channel is ChannelStatus.Failed && (a.channel as ChannelStatus.Failed).nextRetryAtMillis == null) {
            LinkButton("Try again", onClick = vm::retryChannel)
        }
    }

    SectionHeader("Battery", trailing = { LinkButton("History") { onNavigate(app.auralynx.ui.Route.Battery) } })
    AuralynxCard {
        val b = a.battery
        if (b.isEmpty()) {
            Text("No battery data yet. Values appear when your accessory reports them.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val order = if (a.model.formFactor == FormFactor.EARBUDS_WITH_CASE) listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE, BatteryComponent.SINGLE)
                else listOf(BatteryComponent.SINGLE, BatteryComponent.LEFT, BatteryComponent.RIGHT)
                order.mapNotNull { c -> b[c]?.let { c to it } }.forEach { (c, r) ->
                    BatteryGauge(componentName(c), r.level, r.charging == ChargingState.CHARGING, precisionNote = r.note())
                }
            }
        }
        a.ble?.let { InfoNote("Advertisement ${it.match.label()}.") }
    }

    val ear = a.aap?.budsInEar ?: a.ble?.sighting?.status?.let { (if (it.leftInEar) 1 else 0) + (if (it.rightInEar) 1 else 0) }
    if (a.capabilities.earDetection && ear != null && a.model.formFactor == FormFactor.EARBUDS_WITH_CASE) {
        SectionHeader("Wearing")
        AuralynxCard { Text(when (ear) { 0 -> "Not in ear"; 1 -> "One earbud in ear"; else -> "Both earbuds in ear" }, style = MaterialTheme.typography.titleMedium) }
    }

    if (Feature.NOISE_CONTROL in a.model.declaredFeatures) {
        SectionHeader("Noise control", trailing = { LinkButton("More") { onNavigate(app.auralynx.ui.Route.NoiseControl) } })
        AuralynxCard {
            if (a.capabilities.noiseControl) {
                ModeSelector(a, vm)
            } else {
                val l = a.capabilities.support.getValue(Feature.NOISE_CONTROL).label()
                StatusPill(l.status, l.kind)
                l.explanation?.let { InfoNote(it) }
            }
        }
    }

    SectionHeader("Features on this accessory")
    AuralynxCard {
        listOf(Feature.BATTERY, Feature.EAR_DETECTION, Feature.NOISE_CONTROL, Feature.ADAPTIVE_AUDIO, Feature.CONVERSATION_AWARENESS, Feature.STEM_PRESS_EVENTS, Feature.LOUD_SOUND_REDUCTION)
            .filter { a.capabilities.support[it] !is app.auralynx.core.protocol.devices.FeatureSupport.NotSupportedByModel }
            .forEach { f ->
                val l = a.capabilities.support.getValue(f).label()
                app.auralynx.core.designsystem.CapabilityRow(f.title(), l.status, l.kind, l.explanation)
            }
    }

    SectionHeader("Shortcuts")
    AuralynxCard {
        if (a.model.isAirPodsMax) NavRow("AirPods Max dashboard") { onNavigate(app.auralynx.ui.Route.AirPodsMax) }
        NavRow("Press gestures") { onNavigate(app.auralynx.ui.Route.Gestures) }
        NavRow("Find this accessory") { onNavigate(app.auralynx.ui.Route.Finder) }
        NavRow("All devices") { onNavigate(app.auralynx.ui.Route.Discovery) }
    }
}

@Composable
fun ModeSelector(a: AccessoryState, vm: DeviceViewModel) {
    val modes = a.model.listeningModes.toList().sortedBy { it.wire }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, m ->
            SegmentedButton(
                selected = a.aap?.listeningMode == m,
                onClick = { vm.setMode(m) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                label = { Text(shortLabel(m), maxLines = 1) },
            )
        }
    }
    a.aap?.listeningMode?.let { InfoNote("Current: ${it.label()} (reported by the accessory)") } ?: InfoNote("Current mode not reported yet")
}

private fun shortLabel(m: app.auralynx.core.protocol.model.ListeningMode) = when (m) {
    app.auralynx.core.protocol.model.ListeningMode.OFF -> "Off"
    app.auralynx.core.protocol.model.ListeningMode.NOISE_CANCELLATION -> "ANC"
    app.auralynx.core.protocol.model.ListeningMode.TRANSPARENCY -> "Aware"
    app.auralynx.core.protocol.model.ListeningMode.ADAPTIVE -> "Adaptive"
}

fun componentName(c: BatteryComponent) = when (c) {
    BatteryComponent.LEFT -> "Left"; BatteryComponent.RIGHT -> "Right"; BatteryComponent.CASE -> "Case"; BatteryComponent.SINGLE -> "Battery"
}

@Composable
fun NavRow(title: String, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}
