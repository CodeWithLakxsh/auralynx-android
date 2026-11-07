package app.auralynx.ui.automation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auralynx.automation.AutomationRunner
import app.auralynx.automation.ContextCollector
import app.auralynx.core.automation.Rule
import app.auralynx.core.automation.RuleProblem
import app.auralynx.core.automation.RuleValidator
import app.auralynx.core.data.db.PlaceEntity
import app.auralynx.core.data.repo.AudioProfileRepository
import app.auralynx.core.data.repo.AutomationRepository
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.system.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val repo: AutomationRepository,
    private val settingsRepo: SettingsRepository,
    private val runner: AutomationRunner,
    private val location: LocationHelper,
    val collector: ContextCollector,
    profiles: AudioProfileRepository,
) : ViewModel() {
    val rules = repo.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = repo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val places = repo.places.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val audioProfiles = profiles.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val decision = runner.lastDecision
    val override = runner.manualOverride

    private val _editing = MutableStateFlow<Rule?>(null)
    val editing = _editing.asStateFlow()
    private val _problems = MutableStateFlow<Set<RuleProblem>>(emptySet())
    val problems = _problems.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _editing.value = if (id == 0L) Rule(name = "", conditions = emptyList(), actions = emptyList()) else repo.rule(id)
        _problems.value = emptySet()
    }

    fun edit(transform: (Rule) -> Rule) { _editing.value = _editing.value?.let(transform) }

    /** Returns true when saved. */
    suspend fun save(): Boolean {
        val r = _editing.value ?: return false
        val problems = RuleValidator.validate(r)
        _problems.value = problems
        if (problems.isNotEmpty()) return false
        repo.save(r)
        return true
    }

    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun setEnabled(id: Long, on: Boolean) = viewModelScope.launch { repo.setEnabled(id, on) }
    fun setAutomationEnabled(on: Boolean) = viewModelScope.launch { settingsRepo.update { it.copy(automationEnabled = on) } }
    fun toggleProfile(name: String) = viewModelScope.launch {
        settingsRepo.update { s -> s.copy(activeProfiles = if (name in s.activeProfiles) s.activeProfiles - name else s.activeProfiles + name) }
    }
    fun clearOverride() = runner.clearOverride()
    fun evaluate() = viewModelScope.launch { runner.evaluateNow() }

    /** Saves the current location as a named place (requires location permission). */
    fun addCurrentPlace(name: String, radius: Float, onResult: (String) -> Unit) = viewModelScope.launch {
        val loc = location.currentLocation()
        if (loc == null) { onResult("Location unavailable. Grant location permission and turn on location."); return@launch }
        repo.addPlace(PlaceEntity(0, name.ifBlank { "Place" }, loc.latitude, loc.longitude, radius))
        onResult("Saved \"$name\" (accuracy ±${loc.accuracy.toInt()} m)")
    }

    fun deletePlace(p: PlaceEntity) = viewModelScope.launch { repo.deletePlace(p) }
}
