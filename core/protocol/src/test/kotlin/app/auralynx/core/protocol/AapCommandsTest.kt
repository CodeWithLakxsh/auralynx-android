package app.auralynx.core.protocol

import app.auralynx.core.common.Hex
import app.auralynx.core.common.toHex
import app.auralynx.core.protocol.aap.AapCommands
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.PressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Byte layouts are asserted against packets published in public protocol documentation. */
class AapCommandsTest {

    @Test fun handshake_matchesDocumentedPacket() {
        assertEquals("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00", AapCommands.handshake().toHex())
    }

    @Test fun requestNotifications_matchesDocumentedPacket() {
        assertEquals("04 00 04 00 0F 00 FF FF FF FF", AapCommands.requestNotifications().toHex())
    }

    @Test fun hostFeatures_usesD7FlagSet() {
        assertEquals("04 00 04 00 4D 00 D7 00 00 00 00 00 00 00", AapCommands.hostFeatures().toHex())
    }

    @Test fun listeningMode_encodesEachMode() {
        assertEquals("04 00 04 00 09 00 0D 01 00 00 00", AapCommands.setListeningMode(ListeningMode.OFF).toHex())
        assertEquals("04 00 04 00 09 00 0D 02 00 00 00", AapCommands.setListeningMode(ListeningMode.NOISE_CANCELLATION).toHex())
        assertEquals("04 00 04 00 09 00 0D 03 00 00 00", AapCommands.setListeningMode(ListeningMode.TRANSPARENCY).toHex())
        assertEquals("04 00 04 00 09 00 0D 04 00 00 00", AapCommands.setListeningMode(ListeningMode.ADAPTIVE).toHex())
    }

    @Test fun conversationAwareness_onIs01_offIs02() {
        assertEquals("04 00 04 00 09 00 28 01 00 00 00", AapCommands.setConversationAwareness(true).toHex())
        assertEquals("04 00 04 00 09 00 28 02 00 00 00", AapCommands.setConversationAwareness(false).toHex())
    }

    @Test fun adaptiveNoiseLevel_isClamped() {
        assertEquals("04 00 04 00 09 00 2E 64 00 00 00", AapCommands.setAdaptiveNoiseLevel(150).toHex())
        assertEquals("04 00 04 00 09 00 2E 00 00 00 00", AapCommands.setAdaptiveNoiseLevel(-3).toHex())
    }

    @Test fun listeningModeCycle_matchesDocumentedMasks() {
        // "Turns on Adaptive from O, T, ANC" -> 0x0F ; "O and ANC" -> 0x03
        assertEquals("04 00 04 00 09 00 1A 0F 00 00 00",
            AapCommands.setListeningModeCycle(ListeningMode.entries.toSet()).toHex())
        assertEquals("04 00 04 00 09 00 1A 03 00 00 00",
            AapCommands.setListeningModeCycle(setOf(ListeningMode.OFF, ListeningMode.NOISE_CANCELLATION)).toHex())
    }

    @Test fun listeningModeCycle_rejectsSingleMode() {
        assertThrows(IllegalArgumentException::class.java) {
            AapCommands.setListeningModeCycle(setOf(ListeningMode.OFF))
        }
    }

    @Test fun rename_encodesLengthAndUtf8() {
        assertEquals("04 00 04 00 1A 00 01 03 00 41 42 43", AapCommands.rename(" ABC ").toHex())
        assertThrows(IllegalArgumentException::class.java) { AapCommands.rename("   ") }
        assertThrows(IllegalArgumentException::class.java) { AapCommands.rename("x".repeat(33)) }
    }

    @Test fun stemClaim_buildsMask() {
        assertEquals("04 00 04 00 09 00 39 0A 00 00 00",
            AapCommands.claimStemPresses(setOf(PressType.DOUBLE, PressType.LONG)).toHex())
    }

    @Test fun proximityKeyRequest_requestsIrkAndEncKey() {
        assertEquals("04 00 04 00 30 00 05 00", AapCommands.requestProximityKeys().toHex())
    }

    @Test fun headTracking_packetsStartWithOpcode17() {
        val start = AapCommands.startHeadTracking()
        assertEquals("04 00 04 00 17 00", start.copyOfRange(0, 6).toHex())
        assertEquals(Hex.decode("040004001700000010001000 08A102420B080E10021A0501409C0000").toHex(), start.toHex())
    }
}
