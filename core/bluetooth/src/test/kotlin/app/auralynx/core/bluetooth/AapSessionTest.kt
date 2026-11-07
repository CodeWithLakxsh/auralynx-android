package app.auralynx.core.bluetooth

import app.auralynx.core.bluetooth.aap.AapSession
import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.Outcome
import app.auralynx.core.common.toHex
import app.auralynx.core.protocol.aap.AapCommands
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.ListeningMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AapSessionTest {

    private fun TestScope.session(conn: FakeAccessoryConnection) = AapSession(conn, backgroundScope, confirmTimeoutMillis = 1_000)

    @Test fun start_sendsDocumentedInitialisationSequence() = runTest {
        val conn = FakeAccessoryConnection()
        session(conn).start()
        assertEquals(
            listOf(AapCommands.handshake(), AapCommands.hostFeatures(), AapCommands.requestNotifications(), AapCommands.requestProximityKeys()).map { it.toHex() },
            conn.sent.map { it.toHex() },
        )
    }

    @Test fun notifications_updateState_andRecordObservedFeatures() = runTest {
        val conn = FakeAccessoryConnection()
        val s = session(conn); s.start()
        conn.push("04 00 04 00 04 00 02 04 01 4B 02 01 02 01 50 01 01")
        conn.push("04 00 04 00 06 00 00 01")
        conn.push("04 00 04 00 09 00 0D 04 00 00 00")
        runCurrent()
        val st = s.state.value
        assertEquals(75, st.battery.getValue(BatteryComponent.LEFT).level)
        assertEquals(80, st.battery.getValue(BatteryComponent.RIGHT).level)
        assertEquals(1, st.budsInEar)
        assertEquals(ListeningMode.ADAPTIVE, st.listeningMode)
        assertTrue(st.observedFeatures.containsAll(setOf(Feature.BATTERY, Feature.EAR_DETECTION, Feature.NOISE_CONTROL, Feature.ADAPTIVE_AUDIO)))
    }

    @Test fun setListeningMode_succeedsOnlyWhenAccessoryEchoes() = runTest {
        val s = session(FakeAccessoryConnection()); s.start()
        assertEquals(Outcome.Success(Unit), s.setListeningMode(ListeningMode.NOISE_CANCELLATION))
        assertEquals(ListeningMode.NOISE_CANCELLATION, s.state.value.listeningMode)
    }

    @Test fun setListeningMode_reportsUnsupportedWhenAccessoryAnswersDifferently() = runTest {
        // Firmware without adaptive support answers an adaptive request with ANC.
        val conn = FakeAccessoryConnection { p ->
            if (p.toHex() == AapCommands.setListeningMode(ListeningMode.ADAPTIVE).toHex()) listOf(AapCommands.setListeningMode(ListeningMode.NOISE_CANCELLATION))
            else FakeAccessoryConnection.echoControlWrites(p)
        }
        val s = session(conn); s.start()
        assertEquals(Outcome.Failure(FailureReason.UNSUPPORTED_BY_ACCESSORY), s.setListeningMode(ListeningMode.ADAPTIVE))
    }

    @Test fun commandWithoutEcho_timesOut() = runTest {
        val s = session(FakeAccessoryConnection(FakeAccessoryConnection::silent)); s.start()
        assertEquals(Outcome.Failure(FailureReason.TIMEOUT), s.setConversationAwareness(true))
    }

    @Test fun droppedLink_closesSession_andCommandsFailFast() = runTest {
        val conn = FakeAccessoryConnection()
        val s = session(conn); s.start()
        conn.drop()
        runCurrent() // advanceUntilIdle() does not run backgroundScope work
        assertFalse(s.isOpen.value)
        assertEquals("link lost", s.closedCause.value?.message)
        assertEquals(Outcome.Failure(FailureReason.TIMEOUT), s.setListeningMode(ListeningMode.OFF))
    }

    @Test fun packetLog_neverKeepsDeviceInfoOrKeyBytes() = runTest {
        val conn = FakeAccessoryConnection()
        val s = session(conn); s.start()
        conn.push("04 00 04 00 1D 00 02 D5 00 04 00 41 69 72 00 41 33 30 34 38 00 41 00 51 58 4E 52 48 48 59 58 50 36 00")
        conn.push("04 00 04 00 31 00 01 01 00 10 00" + " AA".repeat(16))
        runCurrent()
        val incoming = s.packetLog.value.filter { !it.outgoing }
        assertEquals(2, incoming.size)
        assertTrue(incoming.all { it.hex.contains("redacted") })
    }

    @Test fun unknownPackets_doNotBreakTheSession() = runTest {
        val conn = FakeAccessoryConnection()
        val s = session(conn); s.start()
        conn.push("04 00 04 00 7E 00 01 02 03")
        conn.push("FF FF")
        conn.push("04 00 04 00 09 00 28 01 00 00 00")
        runCurrent()
        assertTrue(s.isOpen.value)
        assertEquals(true, s.state.value.conversationAwareness)
        assertEquals(2, s.state.value.packetsReceived)
    }
}
