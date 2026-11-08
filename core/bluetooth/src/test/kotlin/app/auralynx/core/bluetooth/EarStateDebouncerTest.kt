package app.auralynx.core.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarStateDebouncerTest {
    @Test fun requiresTwoAgreeingReadings() {
        val d = EarStateDebouncer()
        assertFalse(d.offer(2, 0)); assertNull(d.stable)
        assertTrue(d.offer(2, 100)); assertEquals(2, d.stable)
    }

    @Test fun singleStaleFrameIsIgnored() {
        // Pattern captured on OnePlus Pad 3: a stale "both in" frame between "left out" frames.
        val d = EarStateDebouncer()
        d.offer(2, 0); d.offer(2, 10)
        assertFalse(d.offer(1, 20))
        assertFalse(d.offer(2, 20))   // stale frame resets the candidate
        assertFalse(d.offer(1, 30))
        assertTrue(d.offer(1, 40))
        assertEquals(1, d.stable)
        assertEquals(40, d.stableSinceMillis)
    }

    @Test fun outOfOrderReadingsAreDropped() {
        val d = EarStateDebouncer()
        d.offer(2, 100); d.offer(2, 200)
        assertFalse(d.offer(0, 150)); assertFalse(d.offer(0, 160))
        assertEquals(2, d.stable)
    }
}
