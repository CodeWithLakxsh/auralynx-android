package app.auralynx.core.automation

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Snapshot of everything rules may depend on. Unknown signals are `null` / not in [availableSignals]. */
data class AutomationContext(
    val nowMillis: Long,
    val zone: ZoneId,
    val availableSignals: Set<Signal>,
    val connectedAccessories: Set<String> = emptySet(),
    val currentPlaceIds: Set<Long> = emptySet(),
    val wifiSsid: String? = null,
    val foregroundPackage: String? = null,
    val activity: MotionActivity? = null,
    val activeProfiles: Set<String> = emptySet(),
) {
    internal val local: LocalDateTime get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
}

/** The user changed [targets] by hand; automation leaves them alone until [untilMillis]. */
data class ManualOverride(val targets: Set<ActionTarget>, val untilMillis: Long)

data class Conflict(val target: ActionTarget, val winner: Rule, val losers: List<Rule>)

data class Decision(
    /** One action per target, with the rule that produced it. */
    val actions: Map<ActionTarget, Pair<Action, Rule>>,
    val matchedRules: List<Rule>,
    val conflicts: List<Conflict>,
    val suppressedByOverride: Set<ActionTarget>,
    /** Enabled rules that cannot match because a signal is unavailable (usually a missing permission). */
    val blockedRules: Map<Rule, Set<Signal>>,
)

/**
 * Stateless rule evaluator.
 *
 * Conflict policy, applied per [ActionTarget]:
 * 1. higher [Rule.priority] wins;
 * 2. on a tie, the more specific rule (more conditions) wins;
 * 3. on a tie, the most recently edited rule wins;
 * 4. on a tie, the lower id wins (stable ordering).
 * A [Conflict] is reported only when competing rules want *different* values for the same target.
 */
class AutomationEngine {

    fun evaluate(rules: List<Rule>, context: AutomationContext, override: ManualOverride? = null): Decision {
        val enabled = rules.filter { it.enabled && it.conditions.isNotEmpty() && it.actions.isNotEmpty() }

        val blocked = enabled.mapNotNull { rule ->
            val missing = rule.signals - context.availableSignals
            if (missing.isEmpty()) null else rule to missing
        }.toMap()

        val matched = enabled.filter { it !in blocked && it.conditions.all { c -> matches(c, context) } }
            .sortedWith(PRECEDENCE)

        val activeOverride = override?.takeIf { it.untilMillis > context.nowMillis }
        val suppressed = mutableSetOf<ActionTarget>()
        val actions = linkedMapOf<ActionTarget, Pair<Action, Rule>>()
        val conflicts = mutableListOf<Conflict>()

        ActionTarget.entries.forEach { target ->
            val candidates = matched.mapNotNull { rule -> rule.actions.firstOrNull { it.target == target }?.let { it to rule } }
            if (candidates.isEmpty()) return@forEach
            if (activeOverride != null && target in activeOverride.targets) {
                suppressed += target
                return@forEach
            }
            val winner = candidates.first()
            actions[target] = winner
            val losers = candidates.drop(1).filter { it.first != winner.first }.map { it.second }
            if (losers.isNotEmpty()) conflicts += Conflict(target, winner.second, losers)
        }

        return Decision(actions, matched, conflicts, suppressed, blocked)
    }

    fun matches(condition: Condition, ctx: AutomationContext): Boolean = when (condition) {
        is Condition.TimeWindow -> inWindow(condition, ctx.local)
        is Condition.AtPlace -> condition.placeId in ctx.currentPlaceIds
        is Condition.OnWifi -> ctx.wifiSsid != null && ctx.wifiSsid.equals(condition.ssid, ignoreCase = false)
        is Condition.AccessoryConnected -> if (condition.address == null) {
            ctx.connectedAccessories.isNotEmpty()
        } else {
            ctx.connectedAccessories.any { it.equals(condition.address, ignoreCase = true) }
        }
        is Condition.AppInForeground -> ctx.foregroundPackage == condition.packageName
        is Condition.Activity -> ctx.activity == condition.activity
        is Condition.ProfileActive -> condition.profile in ctx.activeProfiles
    }

    /**
     * Next local time at which any time window of an enabled rule opens or closes, used to schedule a
     * single inexact alarm instead of polling. Returns null when no enabled rule has a time window.
     */
    fun nextTimeBoundaryMillis(rules: List<Rule>, nowMillis: Long, zone: ZoneId): Long? {
        val windows = rules.filter { it.enabled }.flatMap { r -> r.conditions.filterIsInstance<Condition.TimeWindow>() }
        if (windows.isEmpty()) return null
        val minutes = windows.flatMap { listOf(it.startMinute, it.endMinute) }.toSortedSet()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).truncatedTo(ChronoUnit.MINUTES)
        val nowMinute = now.hour * 60 + now.minute
        val nextToday = minutes.firstOrNull { it > nowMinute }
        val target = if (nextToday != null) {
            now.withHour(nextToday / 60).withMinute(nextToday % 60)
        } else {
            val first = minutes.first()
            now.plusDays(1).withHour(first / 60).withMinute(first % 60)
        }
        return target.atZone(zone).toInstant().toEpochMilli()
    }

    private fun inWindow(w: Condition.TimeWindow, t: LocalDateTime): Boolean {
        val minute = t.hour * 60 + t.minute
        return if (w.startMinute < w.endMinute) {
            t.dayOfWeek in w.days && minute >= w.startMinute && minute < w.endMinute
        } else {
            // Overnight window: the evening part belongs to today, the morning part to yesterday's window.
            (t.dayOfWeek in w.days && minute >= w.startMinute) ||
                (t.dayOfWeek.minus(1) in w.days && minute < w.endMinute)
        }
    }

    companion object {
        val PRECEDENCE: Comparator<Rule> = compareByDescending<Rule> { it.priority }
            .thenByDescending { it.conditions.size }
            .thenByDescending { it.updatedAtMillis }
            .thenBy { it.id }
    }
}
