package app.auralynx.ui.automation

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auralynx.core.automation.Action
import app.auralynx.core.automation.Condition
import app.auralynx.core.automation.MotionActivity
import app.auralynx.core.automation.RequiredPermission
import app.auralynx.core.automation.Rule
import app.auralynx.core.automation.RuleProblem
import app.auralynx.core.automation.Signal
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.SettingSwitch
import app.auralynx.core.designsystem.StateMessage
import app.auralynx.core.designsystem.StatusKind
import app.auralynx.core.designsystem.StatusPill
import app.auralynx.core.designsystem.Tokens
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.system.label
import app.auralynx.ui.Route
import app.auralynx.ui.common.formatTime
import app.auralynx.ui.device.Dropdown
import app.auralynx.ui.device.ScreenColumn
import kotlinx.coroutines.launch
import java.time.DayOfWeek

val PRESET_PROFILES = listOf("Studying", "Commute", "Workout", "Focus", "Sleep")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationScreen(vm: AutomationViewModel, onNavigate: (Route) -> Unit) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val decision by vm.decision.collectAsStateWithLifecycle()
    val override by vm.override.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val profiles by vm.audioProfiles.collectAsStateWithLifecycle()
    ScreenColumn {
        Text("Smart automation", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Tokens.lg))
        AuralynxCard {
            SettingSwitch("Automation", settings?.automationEnabled == true, vm::setAutomationEnabled,
                supporting = "Rules run on events and at time boundaries. No continuous polling.")
            override?.takeIf { it.untilMillis > System.currentTimeMillis() }?.let { o ->
                InfoNote("Manual override: ${o.targets.joinToString { it.name.lowercase().replace('_', ' ') }} until ${formatTime(o.untilMillis)}")
                TextButton(onClick = vm::clearOverride) { Text("Resume automation now") }
            }
        }

        SectionHeader("Profiles")
        AuralynxCard {
            InfoNote("Turn a profile on to trigger rules that use it, for example \"Studying → Noise Cancellation\".")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                (PRESET_PROFILES + settings?.activeProfiles.orEmpty()).distinct().forEach { p ->
                    FilterChip(selected = p in settings?.activeProfiles.orEmpty(), onClick = { vm.toggleProfile(p) }, label = { Text(p) })
                }
            }
        }

        SectionHeader("Rules", trailing = { TextButton(onClick = { onNavigate(Route.RuleEditor(0)) }) { Text("New rule") } })
        if (rules.isEmpty()) {
            StateMessage(Icons.Outlined.AutoMode, "No rules yet", "Example: when you are at a saved place and your AirPods are connected, switch to Transparency.",
                actionLabel = "Create rule", onAction = { onNavigate(Route.RuleEditor(0)) })
        } else AuralynxCard {
            rules.forEach { r ->
                val blocked = decision?.blockedRules?.get(r)
                val active = decision?.matchedRules?.any { it.id == r.id } == true
                ListItem(
                    headlineContent = { Text(r.name) },
                    supportingContent = {
                        Text(describe(r, places.associate { it.id to it.name }, profiles.associate { it.id to it.name }) + (blocked?.let { "\nWaiting for permission: " + it.joinToString { s -> s.name.lowercase() } } ?: ""))
                    },
                    overlineContent = { if (active) StatusPill("Active now", StatusKind.LIVE) },
                    trailingContent = { Switch(r.enabled, { vm.setEnabled(r.id, it) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onNavigate(Route.RuleEditor(r.id)) },
                )
            }
        }

        SectionHeader("History")
        AuralynxCard {
            if (history.isEmpty()) Text("Nothing has run yet.")
            history.take(25).forEach { h ->
                Text("${formatTime(h.timestampMillis)} · ${h.ruleName}: ${h.action} — ${h.outcome.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun describe(r: Rule, placeNames: Map<Long, String>, profileNames: Map<Long, String> = emptyMap()): String {
    val whenPart = r.conditions.joinToString(" and ") { c ->
        when (c) {
            is Condition.TimeWindow -> "%02d:%02d–%02d:%02d".format(c.startMinute / 60, c.startMinute % 60, c.endMinute / 60, c.endMinute % 60) +
                if (c.days.size < 7) " (" + c.days.sorted().joinToString(",") { it.name.take(3).lowercase() } + ")" else ""
            is Condition.AtPlace -> "at ${placeNames[c.placeId] ?: "place #${c.placeId}"}"
            is Condition.OnWifi -> "on Wi-Fi \"${c.ssid}\""
            is Condition.AccessoryConnected -> "accessory connected"
            is Condition.AppInForeground -> "using ${c.packageName}"
            is Condition.Activity -> c.activity.name.lowercase()
            is Condition.ProfileActive -> "profile ${c.profile}"
        }
    }
    val thenPart = r.actions.joinToString(", ") { a ->
        when (a) {
            is Action.SetListeningMode -> a.mode.label()
            is Action.SetConversationAwareness -> "conversation awareness " + if (a.enabled) "on" else "off"
            is Action.ApplyAudioProfile -> "audio profile " + (profileNames[a.profileId] ?: "#${a.profileId}")
        }
    }
    return "When $whenPart → $thenPart · priority ${r.priority}"
}

private enum class ConditionKind(val label: String) { TIME("Time"), PLACE("Place"), WIFI("Wi-Fi"), ACCESSORY("Accessory connected"), APP("App in use"), ACTIVITY("Activity"), PROFILE("Profile") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(vm: AutomationViewModel, ruleId: Long, onDone: () -> Unit) {
    LaunchedEffect(ruleId) { vm.load(ruleId) }
    val rule by vm.editing.collectAsStateWithLifecycle()
    val problems by vm.problems.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val profiles by vm.audioProfiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var placeMessage by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val r = rule ?: return

    ScreenColumn {
        OutlinedTextField(r.name, { v -> vm.edit { it.copy(name = v) } }, label = { Text("Rule name") }, singleLine = true,
            isError = RuleProblem.EMPTY_NAME in problems, modifier = Modifier.fillMaxWidth().padding(top = Tokens.md))

        SectionHeader("When all of these are true")
        AuralynxCard {
            if (r.conditions.isEmpty()) Text("Add at least one condition.", color = if (RuleProblem.NO_CONDITIONS in problems) MaterialTheme.colorScheme.error else Color.Unspecified)
            r.conditions.forEachIndexed { i, c ->
                ListItem(headlineContent = { Text(describe(r.copy(conditions = listOf(c), actions = emptyList()), places.associate { it.id to it.name }).removePrefix("When ").substringBefore(" →")) },
                    trailingContent = { IconButton(onClick = { vm.edit { it.copy(conditions = it.conditions.filterIndexed { j, _ -> j != i }) } }) { Icon(Icons.Outlined.Delete, "Remove condition") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            if (RuleProblem.CONTRADICTORY_CONDITIONS in problems) Text("These conditions can never be true together.", color = MaterialTheme.colorScheme.error)
            ConditionAdder(places, onAdd = { c -> vm.edit { it.copy(conditions = it.conditions + c) } })
        }

        val needed = r.requiredPermissions
        if (needed.isNotEmpty()) {
            val missing = needed.filter { p -> when (p) {
                RequiredPermission.LOCATION -> Signal.LOCATION !in vm.collector.availableSignals()
                RequiredPermission.USAGE_ACCESS -> !vm.collector.hasUsageAccess()
                RequiredPermission.ACTIVITY_RECOGNITION -> Signal.ACTIVITY !in vm.collector.availableSignals()
                RequiredPermission.BLUETOOTH -> false
            } }
            if (missing.isNotEmpty()) AuralynxCard {
                InfoNote("This rule needs: " + missing.joinToString { it.name.lowercase().replace('_', ' ') } + ". Permissions are requested only now, because you added a condition that uses them.")
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                    if (RequiredPermission.LOCATION in missing) OutlinedButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
                    if (RequiredPermission.ACTIVITY_RECOGNITION in missing && Build.VERSION.SDK_INT >= 29) OutlinedButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) }) { Text("Allow activity") }
                    if (RequiredPermission.USAGE_ACCESS in missing) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Usage access") }
                }
            }
        }

        SectionHeader("Then")
        AuralynxCard {
            var mode by remember { mutableStateOf<ListeningMode?>(r.actions.filterIsInstance<Action.SetListeningMode>().firstOrNull()?.mode) }
            Dropdown("Listening mode", listOf<ListeningMode?>(null) + ListeningMode.entries, mode, { it?.label() ?: "Don't change" }) { m ->
                mode = m
                vm.edit { it.copy(actions = it.actions.filterNot { a -> a is Action.SetListeningMode } + listOfNotNull(m?.let { Action.SetListeningMode(it) })) }
            }
            Spacer(Modifier.height(Tokens.sm))
            val ca = r.actions.filterIsInstance<Action.SetConversationAwareness>().firstOrNull()?.enabled
            Dropdown("Conversation awareness", listOf<Boolean?>(null, true, false), ca, { when (it) { null -> "Don't change"; true -> "On"; false -> "Off" } }) { v ->
                vm.edit { it.copy(actions = it.actions.filterNot { a -> a is Action.SetConversationAwareness } + listOfNotNull(v?.let { Action.SetConversationAwareness(it) })) }
            }
            Spacer(Modifier.height(Tokens.sm))
            val prof = r.actions.filterIsInstance<Action.ApplyAudioProfile>().firstOrNull()?.profileId
            Dropdown("Audio profile", listOf<Long?>(null) + profiles.map { it.id }, prof, { id -> id?.let { i -> profiles.firstOrNull { it.id == i }?.name } ?: "Don't change" }) { v ->
                vm.edit { it.copy(actions = it.actions.filterNot { a -> a is Action.ApplyAudioProfile } + listOfNotNull(v?.let { Action.ApplyAudioProfile(it) })) }
            }
            if (RuleProblem.NO_ACTIONS in problems) Text("Choose at least one action.", color = MaterialTheme.colorScheme.error)
            InfoNote("Actions that need the accessory channel are skipped (and logged) when it is unavailable.")
        }

        SectionHeader("Priority")
        AuralynxCard {
            Text("Priority ${r.priority}. When rules disagree, higher priority wins; then the rule with more conditions; then the most recently edited.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Slider(r.priority.toFloat(), { v -> vm.edit { it.copy(priority = v.toInt()) } }, valueRange = 0f..10f, steps = 9)
        }

        SectionHeader("Saved places")
        AuralynxCard {
            var name by remember { mutableStateOf("") }
            places.forEach { p ->
                ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("Radius ${p.radiusMeters.toInt()} m") },
                    trailingContent = { IconButton(onClick = { vm.deletePlace(p) }) { Icon(Icons.Outlined.Delete, "Delete place") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            OutlinedTextField(name, { name = it }, label = { Text("New place name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { vm.addCurrentPlace(name, 150f) { placeMessage = it } }) { Text("Save current location") }
            placeMessage?.let { InfoNote(it) }
        }

        Row(Modifier.padding(top = Tokens.lg), horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
            Button(onClick = { scope.launch { if (vm.save()) onDone() } }) { Text("Save rule") }
            if (r.id != 0L) OutlinedButton(onClick = { vm.delete(r.id); onDone() }) { Text("Delete") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionAdder(places: List<app.auralynx.core.data.db.PlaceEntity>, onAdd: (Condition) -> Unit) {
    var kind by remember { mutableStateOf<ConditionKind?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
        ConditionKind.entries.forEach { k -> AssistChip(onClick = { kind = k }, label = { Text(k.label) }) }
    }
    when (kind) {
        ConditionKind.TIME -> {
            var start by remember { mutableStateOf("09:00") }; var end by remember { mutableStateOf("17:00") }
            var days by remember { mutableStateOf(DayOfWeek.entries.toSet()) }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                OutlinedTextField(start, { start = it }, label = { Text("From (HH:MM)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it }, label = { Text("To (HH:MM)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp())) {
                DayOfWeek.entries.forEach { d -> FilterChip(d in days, { days = if (d in days) days - d else days + d }, { Text(d.name.take(2)) }) }
            }
            val s = parseMinutes(start); val e = parseMinutes(end)
            TextButton(enabled = s != null && e != null && s != e && days.isNotEmpty(), onClick = { onAdd(Condition.TimeWindow(s!!, e!!, days)); kind = null }) { Text("Add time window") }
        }
        ConditionKind.PLACE -> if (places.isEmpty()) InfoNote("Save a place below first.") else places.forEach { p ->
            TextButton(onClick = { onAdd(Condition.AtPlace(p.id)); kind = null }) { Text("At ${p.name}") }
        }
        ConditionKind.WIFI -> TextInputAdder("Network name (SSID)") { onAdd(Condition.OnWifi(it)); kind = null }
        ConditionKind.ACCESSORY -> TextButton(onClick = { onAdd(Condition.AccessoryConnected()); kind = null }) { Text("Any supported accessory connected") }
        ConditionKind.APP -> TextInputAdder("App package name, e.g. com.example.music") { onAdd(Condition.AppInForeground(it.trim())); kind = null }
        ConditionKind.ACTIVITY -> Row {
            MotionActivity.entries.forEach { a -> TextButton(onClick = { onAdd(Condition.Activity(a)); kind = null }) { Text(a.name.lowercase().replaceFirstChar { it.uppercase() }) } }
        }
        ConditionKind.PROFILE -> FlowRow { PRESET_PROFILES.forEach { p -> TextButton(onClick = { onAdd(Condition.ProfileActive(p)); kind = null }) { Text(p) } } }
        null -> Unit
    }
}

@Composable
private fun TextInputAdder(label: String, onAdd: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    OutlinedTextField(v, { v = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    TextButton(enabled = v.isNotBlank(), onClick = { onAdd(v) }) { Text("Add") }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

fun parseMinutes(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}
