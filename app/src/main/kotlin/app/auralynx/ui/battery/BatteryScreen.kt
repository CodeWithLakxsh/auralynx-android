package app.auralynx.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.core.data.db.BatterySampleEntity
import app.auralynx.core.data.repo.BatteryAnalytics
import app.auralynx.core.data.repo.BatteryRepository
import app.auralynx.core.data.repo.DischargeEstimate
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.EstimateLabel
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.LineChart
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.SettingSwitch
import app.auralynx.core.designsystem.StateMessage
import app.auralynx.core.designsystem.Tokens
import app.auralynx.ui.common.formatTime
import app.auralynx.ui.device.ScreenColumn
import app.auralynx.ui.device.componentName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BatteryUi(
    val deviceName: String? = null,
    val samples: List<BatterySampleEntity> = emptyList(),
    val estimates: Map<String, DischargeEstimate?> = emptyMap(),
    val charges: List<BatteryAnalytics.ChargeSession> = emptyList(),
    val windowHours: Int = 24,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BatteryViewModel @Inject constructor(
    accessories: AccessoryManager,
    private val battery: BatteryRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val window = MutableStateFlow(24)
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ui = combine(accessories.accessories, window) { list, h -> list.firstOrNull() to h }.flatMapLatest { (a, h) ->
        if (a == null) flowOf(BatteryUi(windowHours = h)) else {
            kotlinx.coroutines.flow.flow {
                battery.observe(a.address, System.currentTimeMillis() - h * 3_600_000L).collect { samples ->
                    val comps = samples.map { it.component }.distinct()
                    emit(BatteryUi(
                        deviceName = a.name, samples = samples, windowHours = h,
                        estimates = comps.filter { it != "CASE" }.associateWith { BatteryAnalytics.estimate(BatteryRepository.toPoints(samples, it)) },
                        charges = comps.flatMap { BatteryAnalytics.chargeSessions(BatteryRepository.toPoints(samples, it)) },
                    ))
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatteryUi())

    fun setWindow(hours: Int) { window.value = hours }
    fun update(t: (app.auralynx.core.data.settings.AppSettings) -> app.auralynx.core.data.settings.AppSettings) = viewModelScope.launch { settingsRepo.update(t) }
}

@Composable
fun BatteryScreen(vm: BatteryViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    ScreenColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm), modifier = Modifier) {
            listOf(6, 24, 72, 168).forEach { h ->
                FilterChip(selected = ui.windowHours == h, onClick = { vm.setWindow(h) }, label = { Text(if (h < 48) "${h} h" else "${h / 24} d") })
            }
        }
        if (ui.samples.isEmpty()) {
            StateMessage(androidx.compose.material.icons.Icons.Outlined.BatteryStd, "No history yet",
                "Battery history fills in as your accessory reports values. Auralynx only records measurements it actually receives.")
        } else {
            SectionHeader("History")
            AuralynxCard {
                val start = System.currentTimeMillis() - ui.windowHours * 3_600_000L
                val span = (ui.windowHours * 3_600_000L).toFloat()
                val series = ui.samples.groupBy { it.component }.map { (c, list) ->
                    componentName(app.auralynx.core.protocol.model.BatteryComponent.valueOf(c)) to list.map { ((it.timestampMillis - start) / span) to it.level.toFloat() }
                }
                val summary = series.joinToString("; ") { (n, pts) -> "$n from ${pts.first().second.toInt()}% to ${pts.last().second.toInt()}%" }
                LineChart(series, "Battery history over ${ui.windowHours} hours: $summary")
                InfoNote("${ui.samples.size} measurements. Values from Bluetooth advertisements have 10 % steps.")
            }
            SectionHeader("Discharge")
            AuralynxCard {
                Row { EstimateLabel(text = "Calculated estimate") }
                ui.estimates.forEach { (c, e) ->
                    val name = componentName(app.auralynx.core.protocol.model.BatteryComponent.valueOf(c))
                    if (e == null) Text("$name: not enough data yet (needs 3+ readings over 10+ minutes of use)", style = MaterialTheme.typography.bodyMedium)
                    else Text("$name: ${"%.1f".format(e.ratePercentPerHour)} %/h" + (e.remainingMinutes?.let { " · about ${it / 60} h ${it % 60} min left" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                }
                InfoNote("Estimated from the most recent continuous use with a least-squares fit. Battery health is not reported by the accessory, so Auralynx does not show one.")
            }
            if (ui.charges.isNotEmpty()) {
                SectionHeader("Charging observed")
                AuralynxCard {
                    ui.charges.takeLast(5).forEach { c -> Text("${formatTime(c.startMillis)}: ${c.fromLevel}% → ${c.toLevel}% in ${(c.endMillis - c.startMillis) / 60_000} min") }
                }
            }
        }
        SectionHeader("Alerts")
        AuralynxCard {
            val st = s ?: return@AuralynxCard
            SettingSwitch("Low battery alert", st.batteryAlertsEnabled, { v -> vm.update { it.copy(batteryAlertsEnabled = v) } }, supporting = "At ${st.lowBatteryThreshold} % or below")
            androidx.compose.material3.Slider(st.lowBatteryThreshold.toFloat(), { v -> vm.update { it.copy(lowBatteryThreshold = v.toInt()) } }, valueRange = 5f..50f, steps = 8)
            SettingSwitch("Fully charged alert", st.chargingAlertsEnabled, { v -> vm.update { it.copy(chargingAlertsEnabled = v) } })
        }
    }
}
