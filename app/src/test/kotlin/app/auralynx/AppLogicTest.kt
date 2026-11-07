package app.auralynx

import app.auralynx.core.automation.Action
import app.auralynx.core.automation.Condition
import app.auralynx.core.automation.Rule
import app.auralynx.core.designsystem.StatusKind
import app.auralynx.core.protocol.devices.ChannelUnavailableReason
import app.auralynx.core.protocol.devices.FeatureEvidence
import app.auralynx.core.protocol.devices.FeatureSupport
import app.auralynx.core.protocol.devices.Transport
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.MeasurementSource
import app.auralynx.ui.Route
import app.auralynx.ui.automation.describe
import app.auralynx.ui.automation.parseMinutes
import app.auralynx.ui.common.label
import app.auralynx.ui.common.note
import app.auralynx.ui.routeForDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {
    @Test fun deepLinks_mapToRoutes() {
        assertEquals(Route.Battery, routeForDeepLink("battery"))
        assertEquals(Route.Finder, routeForDeepLink("finder"))
        assertNull(routeForDeepLink("unknown"))
        assertNull(routeForDeepLink(null))
    }

    @Test fun timeParsing() {
        assertEquals(9 * 60 + 5, parseMinutes(" 09:05 "))
        assertNull(parseMinutes("24:00"))
        assertNull(parseMinutes("9"))
    }

    @Test fun ruleDescription_isHumanReadable() {
        val r = Rule(1, "Office", priority = 3, conditions = listOf(Condition.AtPlace(7), Condition.AccessoryConnected()),
            actions = listOf(Action.SetListeningMode(ListeningMode.TRANSPARENCY)))
        assertEquals("When at Work and accessory connected → Transparency · priority 3", describe(r, mapOf(7L to "Work")))
    }

    @Test fun capabilityLabels_neverClaimAvailabilityForUnavailableFeatures() {
        assertEquals(StatusKind.LIVE, FeatureSupport.Available(Transport.BLE_ADVERTISEMENT, FeatureEvidence.DECLARED).label().kind)
        listOf(
            FeatureSupport.NeedsAccessoryChannel(ChannelUnavailableReason.OS_UNSUPPORTED),
            FeatureSupport.RequiresPrivilegedAccess, FeatureSupport.Experimental, FeatureSupport.NotSupportedByModel,
        ).forEach { assertTrue(it.toString(), it.label().kind != StatusKind.LIVE) }
        assertTrue(FeatureSupport.Available(Transport.ACCESSORY_CHANNEL, FeatureEvidence.UNVERIFIED).label().explanation!!.contains("unverified"))
    }

    @Test fun precisionNotes_stateResolution() {
        assertTrue(MeasurementSource.BLE_ADVERTISEMENT.note().contains("10"))
        assertTrue(MeasurementSource.ACCESSORY_CHANNEL.note().contains("1 %"))
    }
}
