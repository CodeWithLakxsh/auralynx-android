package app.auralynx.experimental.research

import app.auralynx.core.common.Hex
import app.auralynx.core.protocol.aap.AapEvent
import app.auralynx.core.protocol.ble.Continuity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchTest {
    @Test fun nearbyInfo_decodesActionAndStatusNibbles() {
        val msg = Continuity.parse(Hex.decode("10 05 1A 18 AA BB CC")).single()
        val info = ContinuityExplorer.decodeNearbyInfo(msg)!!
        assertEquals(0x0A, info.actionCode)
        assertEquals(0x01, info.statusFlags)
        assertEquals(0x18, info.dataFlags)
        assertTrue(info.possiblyWatchOnWrist)
        assertTrue(info.actionDescription.contains("published interpretation"))
    }

    @Test fun headTracking_requiresCalibrationBeforeReportingYaw() {
        val i = HeadTrackingInterpreter()
        fun s(v: Int) = AapEvent.HeadTracking(v, 0, 0, 0, 0)
        assertNull(i.yawDegrees(s(100)))
        i.setForward(s(100))
        assertNull(i.yawDegrees(s(150)))
        i.setNinetyDegrees(s(1100))
        assertEquals(45.0, i.yawDegrees(s(600))!!, 1e-9)
        assertEquals(-90.0, i.yawDegrees(s(-900))!!, 1e-9)
    }
}
