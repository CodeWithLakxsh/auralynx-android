package app.auralynx.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.auralynx.core.automation.Action
import app.auralynx.core.automation.ActionTarget
import app.auralynx.core.automation.AutomationContext
import app.auralynx.core.automation.AutomationEngine
import app.auralynx.core.automation.ManualOverride
import app.auralynx.core.automation.Rule
import app.auralynx.core.bluetooth.AccessoryManager
import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.common.Clock
import app.auralynx.core.common.Diag
import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.Outcome
import app.auralynx.core.data.repo.AutomationRepository
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.receivers.AutomationAlarmReceiver
import app.auralynx.system.AudioProfileApplier
import app.auralynx.system.label
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates automation rules whenever a relevant signal changes (event-driven, debounced) and at
 * time-window boundaries (one inexact alarm). Applies each target only when its value would change.
 */
@Singleton
class AutomationRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: AutomationRepository,
    private val settings: SettingsRepository,
    private val accessories: AccessoryManager,
    private val collector: ContextCollector,
    private val engine: AutomationEngine,
    private val audioProfiles: AudioProfileApplier,
    private val profileRepo: app.auralynx.core.data.repo.AudioProfileRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val override = MutableStateFlow<ManualOverride?>(null)
    private val lastApplied = mutableMapOf<Pair<String, ActionTarget>, Action>()
    private val attempted = java.util.concurrent.ConcurrentHashMap<Pair<String, ActionTarget>, Action>()
    @Volatile private var lastAccessoryKey: Set<Pair<String, String?>>? = null
    private val _lastDecision = MutableStateFlow<app.auralynx.core.automation.Decision?>(null)
    val lastDecision: StateFlow<app.auralynx.core.automation.Decision?> = _lastDecision.asStateFlow()
    val manualOverride: StateFlow<ManualOverride?> = override.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            // React only to meaningful accessory changes (which accessories are connected and whether
            // their accessory channel is up), not to every BLE advertisement (~1/s while connected).
            val accessoryKey = accessories.accessories
                .map { list -> list.map { it.address to it.channel::class.simpleName }.toSet() }
                .distinctUntilChanged()
            combine(repo.rules, settings.settings, accessoryKey, collector.changes, override) { rules, s, acc, _, _ ->
                Triple(rules, s, acc)
            }.debounce(750).collect { (rules, s, acc) ->
                // Forget attempts for accessories that are gone or whose channel state changed, so a
                // reconnect retries once.
                attempted.keys.retainAll { k -> acc.any { it.first == k.first } }
                if (acc != lastAccessoryKey) { attempted.clear(); lastAccessoryKey = acc }
                val enabled = rules.filter { it.enabled }
                collector.configure(if (s.automationEnabled) enabled.flatMapTo(mutableSetOf()) { it.signals } else emptySet())
                if (s.automationEnabled) evaluateNow() else scheduleNextBoundary(emptyList())
            }
        }
    }

    /** Called when the user changes a target by hand; automation backs off for the configured time. */
    fun noteManualChange(target: ActionTarget) {
        scope.launch {
            val minutes = settings.current().manualOverrideMinutes
            val until = clock.nowMillis() + minutes * 60_000L
            val current = override.value?.takeIf { it.untilMillis > clock.nowMillis() }
            override.value = ManualOverride((current?.targets ?: emptySet()) + target, until)
        }
    }

    fun clearOverride() { override.value = null }

    suspend fun evaluateNow() = lock.withLock {
        val s = settings.current()
        if (!s.automationEnabled) return@withLock
        val rules = repo.rules.first()
        val connected = accessories.accessories.value
        if (rules.any { r -> r.enabled && r.conditions.any { it is app.auralynx.core.automation.Condition.AtPlace } }) collector.refreshLocation()
        val ctx = AutomationContext(
            nowMillis = clock.nowMillis(),
            zone = ZoneId.systemDefault(),
            availableSignals = collector.availableSignals(),
            connectedAccessories = connected.map { it.address }.toSet(),
            currentPlaceIds = collector.placesContaining(repo.allPlaces()),
            wifiSsid = collector.wifiSsid.value,
            foregroundPackage = if (rules.any { r -> r.conditions.any { it is app.auralynx.core.automation.Condition.AppInForeground } }) collector.foregroundPackage() else null,
            activity = collector.currentActivity(),
            activeProfiles = s.activeProfiles,
        )
        val decision = engine.evaluate(rules, ctx, override.value)
        _lastDecision.value = decision
        decision.conflicts.forEach { c ->
            repo.log(c.winner.id, c.winner.name, c.target.name, "CONFLICT", "Overrode: " + c.losers.joinToString { it.name })
        }
        for ((target, pair) in decision.actions) {
            val (action, rule) = pair
            if (target == ActionTarget.AUDIO_PROFILE) {
                val key = "*" to target
                if (lastApplied[key] == action) continue
                val ok = audioProfiles.apply((action as Action.ApplyAudioProfile).profileId)
                lastApplied[key] = action
                repo.log(rule.id, rule.name, describe(action), if (ok) "APPLIED" else "FAILED_UNSUPPORTED")
                continue
            }
            for (acc in connected) {
                val key = acc.address to target
                if (lastApplied[key] == action) continue
                // Do not retry (or re-log) an action that already failed in this accessory state.
                if (attempted[key] == action) continue
                attempted[key] = action
                val outcome = execute(acc.address, action)
                val label = when (outcome) {
                    is Outcome.Success -> { lastApplied[key] = action; "APPLIED" }
                    is Outcome.Failure -> when (outcome.reason) {
                        FailureReason.NOT_CONNECTED -> "FAILED_NOT_CONNECTED"
                        FailureReason.UNSUPPORTED_BY_ACCESSORY -> "FAILED_UNSUPPORTED"
                        else -> "FAILED_" + outcome.reason.name
                    }
                }
                repo.log(rule.id, rule.name, describe(action), label, acc.name)
            }
        }
        decision.suppressedByOverride.forEach { repo.log(null, "Manual override", it.name, "SKIPPED_OVERRIDE") }
        scheduleNextBoundary(rules)
    }

    private suspend fun execute(address: String, action: Action): Outcome<Unit> = when (action) {
        is Action.SetListeningMode -> accessories.setListeningMode(address, action.mode)
        is Action.SetConversationAwareness -> accessories.setConversationAwareness(address, action.enabled)
        is Action.ApplyAudioProfile -> Outcome.Success(Unit)
    }

    private suspend fun describe(a: Action): String = when (a) {
        is Action.SetListeningMode -> "Listening mode: " + a.mode.label()
        is Action.SetConversationAwareness -> "Conversation awareness: " + if (a.enabled) "on" else "off"
        is Action.ApplyAudioProfile -> "Audio profile: " + (profileRepo.get(a.profileId)?.name ?: "#${a.profileId} (deleted)")
    }

    /** One inexact alarm at the next window boundary; the OS may batch it with others to save power. */
    private fun scheduleNextBoundary(rules: List<Rule>) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, AutomationAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val next = engine.nextTimeBoundaryMillis(rules, clock.nowMillis(), ZoneId.systemDefault())
        if (next == null) { am.cancel(pi); return }
        am.setWindow(AlarmManager.RTC_WAKEUP, next, 5 * 60_000L, pi)
        Diag.d(TAG) { "Next automation boundary in ${(next - clock.nowMillis()) / 60_000} min" }
    }

    companion object { private const val TAG = "AutomationRunner" }
}
