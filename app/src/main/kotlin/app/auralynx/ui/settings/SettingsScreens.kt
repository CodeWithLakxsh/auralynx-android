package app.auralynx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.auralynx.BuildConfig
import app.auralynx.core.data.settings.AppSettings
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.core.data.settings.ThemeMode
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.SettingSwitch
import app.auralynx.core.designsystem.Tokens
import app.auralynx.ui.Route
import app.auralynx.ui.device.NavRow
import app.auralynx.ui.device.ScreenColumn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(t) }
}

@Composable
fun MoreScreen(onNavigate: (Route) -> Unit) {
    ScreenColumn {
        Text("More", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Tokens.lg))
        AuralynxCard {
            NavRow("Devices", "Paired, connected and nearby accessories") { onNavigate(Route.Discovery) }
            NavRow("Find accessory", "Signal-strength finder and last seen") { onNavigate(Route.Finder) }
            NavRow("AirPods Max") { onNavigate(Route.AirPodsMax) }
            NavRow("Security & privacy", "Permissions, history, diagnostics, delete data") { onNavigate(Route.Security) }
            NavRow("Experimental", "Research features, clearly labelled") { onNavigate(Route.Experimental) }
            NavRow("Settings") { onNavigate(Route.Settings) }
            NavRow("About & licenses") { onNavigate(Route.About) }
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val st = s ?: return
    ScreenColumn {
        SectionHeader("Appearance")
        AuralynxCard {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                ThemeMode.entries.forEach { m -> FilterChip(st.themeMode == m, { vm.update { it.copy(themeMode = m) } }, { Text(m.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }) }
            }
            SettingSwitch("Dynamic colour", st.dynamicColor, { v -> vm.update { it.copy(dynamicColor = v) } }, supporting = "Use your wallpaper colours (Android 12+)")
        }
        SectionHeader("Accessory")
        AuralynxCard {
            SettingSwitch("Accessory channel", st.accessoryChannelEnabled, { v -> vm.update { it.copy(accessoryChannelEnabled = v) } },
                supporting = "Apple's accessory protocol for noise control and 1 % battery. Used only if this Android version offers a public API for it (none does yet).")
            SettingSwitch("Pause when an earbud is removed", st.autoPauseOnEarRemoval, { v -> vm.update { it.copy(autoPauseOnEarRemoval = v) } })
            SettingSwitch("Resume when it is put back", st.autoResumeOnEarInsert, { v -> vm.update { it.copy(autoResumeOnEarInsert = v) } })
        }
        SectionHeader("Automation")
        AuralynxCard {
            Text("Manual changes pause automation for ${st.manualOverrideMinutes} min")
            Slider(st.manualOverrideMinutes.toFloat(), { v -> vm.update { it.copy(manualOverrideMinutes = v.toInt()) } }, valueRange = 5f..180f)
        }
        SectionHeader("Data retention")
        AuralynxCard {
            Text("Keep history for ${st.retentionDays} days")
            Slider(st.retentionDays.toFloat(), { v -> vm.update { it.copy(retentionDays = v.toInt()) } }, valueRange = 7f..365f)
            InfoNote("Older battery, connection and automation history is deleted automatically.")
        }
        InfoNote("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}

@Composable
fun AboutScreen() {
    ScreenColumn {
        Text("Auralynx", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Tokens.lg))
        Text(androidx.compose.ui.res.stringResource(app.auralynx.R.string.tagline), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
        Text("Version ${BuildConfig.VERSION_NAME}. Experimental release: hardware validation is incomplete.", style = MaterialTheme.typography.bodyMedium)
        SectionHeader("License")
        AuralynxCard {
            Text("Auralynx is free software under the GNU General Public License v3.0 or later. You may redistribute and modify it under those terms. It comes with no warranty.")
        }
        SectionHeader("Not affiliated")
        AuralynxCard {
            Text("Auralynx is an independent project, not affiliated with, endorsed by or sponsored by Apple Inc. AirPods, AirPods Pro, AirPods Max, Beats and Apple Watch are trademarks of Apple Inc., used only to describe compatibility.")
        }
        SectionHeader("Research credits")
        AuralynxCard {
            Text("Protocol knowledge comes from public reverse-engineering work. Auralynx's implementation is original, but builds on documentation from:", style = MaterialTheme.typography.bodyMedium)
            listOf(
                "LibrePods (GPL-3.0) — AAP protocol definitions",
                "CAPod (GPL-3.0) — proximity pairing format, model table",
                "OpenPods (GPL-3.0) — early BLE status research",
                "apple-wireshark dissector — AAP/Continuity dissection",
                "Martin et al., PETS 2019; Celosia & Cunche, PETS 2020 — Continuity research",
                "Brown & Duda 1998 — spherical head model",
                "R. Bristow-Johnson — Audio EQ Cookbook",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        SectionHeader("Open-source libraries")
        AuralynxCard {
            listOf(
                "AndroidX, Jetpack Compose, Material 3, Room, DataStore, Navigation, Lifecycle, Glance — Apache-2.0",
                "Dagger / Hilt — Apache-2.0",
                "Kotlin, kotlinx.coroutines, kotlinx.serialization — Apache-2.0",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            InfoNote("Full texts: THIRD_PARTY_NOTICES.md in the source repository.")
        }
    }
}
