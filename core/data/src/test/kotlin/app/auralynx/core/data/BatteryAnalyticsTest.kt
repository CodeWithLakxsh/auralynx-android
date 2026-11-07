package app.auralynx.core.data

import app.auralynx.core.data.repo.BatteryAnalytics
import app.auralynx.core.data.repo.BatteryPoint
import app.auralynx.core.protocol.model.ChargingState.CHARGING
import app.auralynx.core.protocol.model.ChargingState.DISCHARGING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryAnalyticsTest {
    private val min = 60_000L

    @Test fun steadyDrain_givesRateAndRemaining() {
        // 10 points per hour, 1 % every 6 minutes -> 10 %/h; at 50 % -> ~300 minutes left
        val points = (0..10).map { BatteryPoint(it * 6 * min, 60 - it, DISCHARGING) }
        val e = BatteryAnalytics.estimate(points)
        assertNotNull(e)
        assertEquals(10.0, e!!.ratePercentPerHour, 0.01)
        assertEquals(300, e.remainingMinutes)
        assertEquals(11, e.samples)
        assertEquals(60, e.spanMinutes)
    }

    @Test fun tooLittleEvidence_returnsNull() {
        assertNull(BatteryAnalytics.estimate(listOf(BatteryPoint(0, 80, DISCHARGING), BatteryPoint(5 * min, 79, DISCHARGING))))
        // three samples but within 4 minutes
        assertNull(BatteryAnalytics.estimate((0..2).map { BatteryPoint(it * 2 * min, 80 - it, DISCHARGING) }))
    }

    @Test fun onlyMostRecentSegmentIsUsed() {
        val old = (0..5).map { BatteryPoint(it * 6 * min, 90 - it * 5, DISCHARGING) }          // fast drain, old
        val charge = listOf(BatteryPoint(40 * min, 70, CHARGING), BatteryPoint(60 * min, 100, CHARGING))
        val recent = (0..6).map { BatteryPoint(70 * min + it * 10 * min, 100 - it, DISCHARGING) } // 6 %/h
        val e = BatteryAnalytics.estimate(old + charge + recent)!!
        assertEquals(6.0, e.ratePercentPerHour, 0.01)
        assertEquals(7, e.samples)
    }

    @Test fun gapSplitsSegments() {
        val first = (0..3).map { BatteryPoint(it * 5 * min, 50 - it, DISCHARGING) }
        val later = (0..3).map { BatteryPoint(200 * min + it * 5 * min, 40 - it, DISCHARGING) }
        val e = BatteryAnalytics.estimate(first + later)!!
        assertEquals(4, e.samples)
    }

    @Test fun flatLevel_hasNoRemainingEstimate() {
        val e = BatteryAnalytics.estimate((0..5).map { BatteryPoint(it * 5 * min, 70, DISCHARGING) })!!
        assertEquals(0.0, e.ratePercentPerHour, 1e-9)
        assertNull(e.remainingMinutes)
    }

    @Test fun chargeSessions_detectsGains() {
        val pts = listOf(
            BatteryPoint(0, 20, DISCHARGING), BatteryPoint(1 * min, 21, CHARGING), BatteryPoint(30 * min, 80, CHARGING),
            BatteryPoint(31 * min, 80, DISCHARGING),
        )
        val s = BatteryAnalytics.chargeSessions(pts).single()
        assertEquals(21, s.fromLevel); assertEquals(80, s.toLevel)
    }
}
