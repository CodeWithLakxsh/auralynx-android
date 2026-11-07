package app.auralynx.core.automation

import app.auralynx.core.protocol.model.ListeningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AutomationEngineTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val engine = AutomationEngine()
    private val allSignals = Signal.entries.toSet()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // 2026-11-09 is a Monday
    private val mondayMorning = at(2026, 11, 9, 9, 30)

    private fun ctx(now: Long = mondayMorning, signals: Set<Signal> = allSignals, block: AutomationContext.() -> AutomationContext = { this }) =
        AutomationContext(now, zone, signals, connectedAccessories = setOf("AA:BB:CC:DD:EE:FF")).block()

    private val office = Rule(
        id = 1, name = "Office", priority = 5,
        conditions = listOf(Condition.AtPlace(10), Condition.AccessoryConnected()),
        actions = listOf(Action.SetListeningMode(ListeningMode.TRANSPARENCY)),
    )
    private val study = Rule(
        id = 2, name = "Study", priority = 5,
        conditions = listOf(Condition.ProfileActive("Studying")),
        actions = listOf(Action.SetListeningMode(ListeningMode.NOISE_CANCELLATION), Action.ApplyAudioProfile(3)),
    )

    @Test fun specExample_enteringOffice_activatesTransparency() {
        val d = engine.evaluate(listOf(office), ctx { copy(currentPlaceIds = setOf(10)) })
        assertEquals(Action.SetListeningMode(ListeningMode.TRANSPARENCY), d.actions[ActionTarget.LISTENING_MODE]?.first)
    }

    @Test fun allConditionsMustHold() {
        val d = engine.evaluate(listOf(office), ctx { copy(currentPlaceIds = setOf(10), connectedAccessories = emptySet()) })
        assertTrue(d.actions.isEmpty())
    }

    @Test fun samePriority_moreSpecificRuleWins_andConflictIsReported() {
        val d = engine.evaluate(listOf(study, office), ctx { copy(currentPlaceIds = setOf(10), activeProfiles = setOf("Studying")) })
        val (action, rule) = d.actions.getValue(ActionTarget.LISTENING_MODE)
        assertEquals("Office", rule.name) // two conditions beat one
        assertEquals(Action.SetListeningMode(ListeningMode.TRANSPARENCY), action)
        assertEquals(1, d.conflicts.size)
        assertEquals("Study", d.conflicts.single().losers.single().name)
        // Non-conflicting targets from the losing rule still apply
        assertEquals(Action.ApplyAudioProfile(3), d.actions[ActionTarget.AUDIO_PROFILE]?.first)
    }

    @Test fun higherPriorityWins() {
        val urgent = study.copy(priority = 9)
        val d = engine.evaluate(listOf(office, urgent), ctx { copy(currentPlaceIds = setOf(10), activeProfiles = setOf("Studying")) })
        assertEquals("Study", d.actions.getValue(ActionTarget.LISTENING_MODE).second.name)
    }

    @Test fun recencyBreaksRemainingTies() {
        val a = study.copy(id = 7, name = "A", updatedAtMillis = 100)
        val b = study.copy(id = 8, name = "B", updatedAtMillis = 200, actions = listOf(Action.SetListeningMode(ListeningMode.OFF)))
        val d = engine.evaluate(listOf(a, b), ctx { copy(activeProfiles = setOf("Studying")) })
        assertEquals("B", d.actions.getValue(ActionTarget.LISTENING_MODE).second.name)
    }

    @Test fun identicalActions_areNotAConflict() {
        val twin = study.copy(id = 9, name = "Twin")
        val d = engine.evaluate(listOf(study, twin), ctx { copy(activeProfiles = setOf("Studying")) })
        assertTrue(d.conflicts.isEmpty())
    }

    @Test fun manualOverride_suppressesTargetUntilExpiry() {
        val context = ctx { copy(currentPlaceIds = setOf(10)) }
        val active = ManualOverride(setOf(ActionTarget.LISTENING_MODE), untilMillis = mondayMorning + 60_000)
        val suppressed = engine.evaluate(listOf(office), context, active)
        assertTrue(suppressed.actions.isEmpty())
        assertEquals(setOf(ActionTarget.LISTENING_MODE), suppressed.suppressedByOverride)

        val expired = ManualOverride(setOf(ActionTarget.LISTENING_MODE), untilMillis = mondayMorning - 1)
        assertEquals(1, engine.evaluate(listOf(office), context, expired).actions.size)
    }

    @Test fun missingPermission_blocksRuleInsteadOfMatching() {
        val d = engine.evaluate(listOf(office), ctx(signals = allSignals - Signal.LOCATION) { copy(currentPlaceIds = setOf(10)) })
        assertTrue(d.actions.isEmpty())
        assertEquals(setOf(Signal.LOCATION), d.blockedRules[office])
    }

    @Test fun disabledRulesAreIgnored() {
        val d = engine.evaluate(listOf(office.copy(enabled = false)), ctx { copy(currentPlaceIds = setOf(10)) })
        assertTrue(d.actions.isEmpty() && d.blockedRules.isEmpty())
    }

    @Test fun timeWindow_sameDay() {
        val work = Condition.TimeWindow(9 * 60, 17 * 60, setOf(DayOfWeek.MONDAY))
        assertTrue(engine.matches(work, ctx()))
        assertTrue(!engine.matches(work, ctx(now = at(2026, 11, 9, 17, 0))))   // end exclusive
        assertTrue(!engine.matches(work, ctx(now = at(2026, 11, 10, 10, 0))))  // Tuesday
    }

    @Test fun timeWindow_overnightBelongsToStartDay() {
        val night = Condition.TimeWindow(22 * 60, 7 * 60, setOf(DayOfWeek.MONDAY))
        assertTrue(engine.matches(night, ctx(now = at(2026, 11, 9, 23, 0))))   // Monday night
        assertTrue(engine.matches(night, ctx(now = at(2026, 11, 10, 6, 59))))  // Tuesday early morning
        assertTrue(!engine.matches(night, ctx(now = at(2026, 11, 9, 6, 0))))   // Monday early = Sunday's window
    }

    @Test fun wifiMatchIsCaseSensitive() {
        assertTrue(engine.matches(Condition.OnWifi("Home"), ctx { copy(wifiSsid = "Home") }))
        assertTrue(!engine.matches(Condition.OnWifi("Home"), ctx { copy(wifiSsid = "home") }))
        assertTrue(!engine.matches(Condition.OnWifi("Home"), ctx()))
    }

    @Test fun nextBoundary_schedulesSingleAlarm() {
        val rule = Rule(1, "Night", conditions = listOf(Condition.TimeWindow(22 * 60, 7 * 60)), actions = listOf(Action.SetConversationAwareness(false)))
        assertEquals(at(2026, 11, 9, 22, 0), engine.nextTimeBoundaryMillis(listOf(rule), mondayMorning, zone))
        assertEquals(at(2026, 11, 10, 7, 0), engine.nextTimeBoundaryMillis(listOf(rule), at(2026, 11, 9, 23, 0), zone))
        assertEquals(null, engine.nextTimeBoundaryMillis(listOf(office), mondayMorning, zone))
    }

    @Test fun validator_flagsProblems() {
        val bad = Rule(name = " ", conditions = listOf(Condition.OnWifi("A"), Condition.OnWifi("B")),
            actions = listOf(Action.SetListeningMode(ListeningMode.OFF), Action.SetListeningMode(ListeningMode.TRANSPARENCY)))
        assertEquals(setOf(RuleProblem.EMPTY_NAME, RuleProblem.DUPLICATE_TARGET, RuleProblem.CONTRADICTORY_CONDITIONS), RuleValidator.validate(bad))
        assertTrue(RuleValidator.validate(office).isEmpty())
    }

    @Test fun requiredPermissions_deriveFromConditions() {
        assertEquals(setOf(RequiredPermission.LOCATION, RequiredPermission.BLUETOOTH), office.requiredPermissions)
        assertTrue(study.requiredPermissions.isEmpty())
    }

    @Test fun codec_roundTrip() {
        val conditions = listOf(Condition.TimeWindow(60, 120, setOf(DayOfWeek.FRIDAY)), Condition.OnWifi("Lab"), Condition.Activity(MotionActivity.WALKING))
        assertEquals(conditions, RuleCodec.decodeConditions(RuleCodec.encodeConditions(conditions)))
        assertEquals(study.actions, RuleCodec.decodeActions(RuleCodec.encodeActions(study.actions)))
    }
}
