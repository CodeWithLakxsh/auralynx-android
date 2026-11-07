package app.auralynx.core.protocol

import app.auralynx.core.protocol.devices.AccessoryModel
import app.auralynx.core.protocol.devices.CapabilityRegistry
import app.auralynx.core.protocol.devices.ChannelUnavailableReason
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.devices.FeatureEvidence
import app.auralynx.core.protocol.devices.FeatureSupport
import app.auralynx.core.protocol.devices.Transport
import app.auralynx.core.protocol.devices.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryTest {

    private val bleOnly = TransportState(
        standardConnected = true, bleObserved = true, channelConnected = false,
        channelUnavailableReason = ChannelUnavailableReason.OS_UNSUPPORTED, experimentalEnabled = false,
    )
    private val channel = bleOnly.copy(channelConnected = true, channelUnavailableReason = null)

    @Test fun withoutChannel_onlyBleFeaturesAreOperable() {
        val caps = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_PRO_2_USBC, bleOnly)
        assertTrue(caps.batteryMonitoring)
        assertTrue(caps.earDetection)
        assertFalse(caps.noiseControl)
        assertEquals(
            FeatureSupport.NeedsAccessoryChannel(ChannelUnavailableReason.OS_UNSUPPORTED),
            caps.support[Feature.NOISE_CONTROL],
        )
        assertEquals(Transport.BLE_ADVERTISEMENT, (caps.support[Feature.BATTERY] as FeatureSupport.Available).transport)
    }

    @Test fun withChannel_declaredFeaturesBecomeAvailable() {
        val caps = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_PRO_2_USBC, channel)
        assertTrue(caps.noiseControl)
        assertTrue(caps.adaptiveAudio)
        assertTrue(caps.conversationAwareness)
        assertEquals(Transport.ACCESSORY_CHANNEL, (caps.support[Feature.BATTERY] as FeatureSupport.Available).transport)
    }

    @Test fun featuresNotOfferedByModel_areNotSupported() {
        val caps = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_2, channel)
        assertEquals(FeatureSupport.NotSupportedByModel, caps.support[Feature.NOISE_CONTROL])
        assertEquals(FeatureSupport.NotSupportedByModel, caps.support[Feature.ADAPTIVE_AUDIO])
        // AirPods Pro 1 has noise control but no adaptive mode
        val pro1 = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_PRO, channel)
        assertTrue(pro1.noiseControl)
        assertFalse(pro1.adaptiveAudio)
    }

    @Test fun vendorIdFeatures_requirePrivilegedAccess() {
        val caps = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_PRO_3, channel)
        assertEquals(FeatureSupport.RequiresPrivilegedAccess, caps.support[Feature.LOUD_SOUND_REDUCTION])
        assertEquals(FeatureSupport.RequiresPrivilegedAccess, caps.support[Feature.HEARING_ASSIST])
    }

    @Test fun experimentalFeatures_stayDisabledUntilOptIn() {
        val off = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_PRO_2, channel)
        assertEquals(FeatureSupport.Experimental, off.support[Feature.HEAD_TRACKING])
        assertFalse(off.headGestures)
        val on = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_PRO_2, channel.copy(experimentalEnabled = true))
        assertTrue(on.isOperable(Feature.HEAD_TRACKING))
    }

    @Test fun spatialAndAdvancedMic_neverClaimedAsAccessoryFeatures() {
        val caps = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_MAX, channel.copy(experimentalEnabled = true))
        assertFalse(caps.spatialAudio)
        assertFalse(caps.advancedMicrophone)
    }

    @Test fun airPodsMax_controlIsMarkedUnverified() {
        val caps = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_MAX_USBC, channel)
        val nc = caps.support[Feature.NOISE_CONTROL] as FeatureSupport.Available
        assertEquals(FeatureEvidence.UNVERIFIED, nc.evidence)
    }

    @Test fun observedBehaviour_upgradesEvidence() {
        val caps = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_MAX_USBC, channel, observed = setOf(Feature.NOISE_CONTROL))
        assertEquals(FeatureEvidence.OBSERVED, (caps.support[Feature.NOISE_CONTROL] as FeatureSupport.Available).evidence)
    }

    @Test fun nothingConnected_nothingOperable() {
        val none = TransportState(false, false, false, ChannelUnavailableReason.NOT_CONNECTED, false)
        val caps = CapabilityRegistry.resolve(AccessoryModel.AIRPODS_PRO_2, none)
        assertTrue(Feature.entries.none { caps.isOperable(it) })
    }

    @Test fun modelLookup() {
        assertEquals(AccessoryModel.AIRPODS_PRO_2_USBC, AccessoryModel.fromBleModelCode(0x2420))
        assertEquals(AccessoryModel.AIRPODS_PRO_3, AccessoryModel.fromModelNumber(" a3064 "))
        assertEquals(AccessoryModel.AIRPODS_MAX, AccessoryModel.guessFromName("Lakshya's AirPods Max"))
        // BLE model codes are unique across the table
        val codes = AccessoryModel.entries.mapNotNull { it.bleModelCode }
        assertEquals(codes.size, codes.toSet().size)
    }
}
