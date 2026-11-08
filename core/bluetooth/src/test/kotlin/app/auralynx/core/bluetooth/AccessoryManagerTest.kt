package app.auralynx.core.bluetooth

import app.auralynx.core.bluetooth.aap.AapTransport
import app.auralynx.core.bluetooth.ble.ProximityScanner
import app.auralynx.core.bluetooth.system.AudioProfile
import app.auralynx.core.bluetooth.system.ConnectedAccessory
import app.auralynx.core.bluetooth.system.SystemBluetoothMonitor
import app.auralynx.core.common.Clock
import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.Outcome
import app.auralynx.core.protocol.devices.ChannelUnavailableReason
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.devices.FeatureSupport
import app.auralynx.core.protocol.model.ListeningMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessoryManagerTest {

    private val address = "AA:BB:CC:DD:EE:01"
    private val connected = MutableStateFlow<Map<String, ConnectedAccessory>>(emptyMap())

    private val sightings = kotlinx.coroutines.flow.MutableSharedFlow<app.auralynx.core.bluetooth.ble.BleSighting>(extraBufferCapacity = 16)

    private fun sighting(addr: String, rssi: Int, statusHex: String = "2B") = app.auralynx.core.bluetooth.ble.BleSighting(
        addr, rssi, 0,
        app.auralynx.core.protocol.ble.ProximityStatus.parse(app.auralynx.core.common.Hex.decode("01 24 20 $statusHex 99 8F 12 00 05"))!!,
        app.auralynx.core.protocol.devices.AccessoryModel.AIRPODS_PRO_2_USBC, null,
    )

    private fun TestScope.manager(transport: FakeTransport): AccessoryManager {
        val system = mockk<SystemBluetoothMonitor>(relaxed = true) {
            every { this@mockk.connected } returns this@AccessoryManagerTest.connected
            every { adapterEnabled } returns MutableStateFlow(true)
        }
        val scanner = mockk<ProximityScanner> { every { proximity(any()) } returns sightings }
        return AccessoryManager(system, transport, scanner, Clock { currentTime }, backgroundScope).also { it.start() }
    }

    private fun connect(name: String = "Test AirPods Pro") {
        connected.value = mapOf(address to ConnectedAccessory(address, name, setOf(AudioProfile.A2DP), null, 0))
    }

    @Test fun connectsChannel_andExposesControls() = runTest {
        val m = manager(FakeTransport())
        connect(); runCurrent()
        val state = m.accessories.value.single()
        assertEquals(ChannelStatus.Connected, state.channel)
        assertTrue(state.capabilities.noiseControl)
        assertEquals(Outcome.Success(Unit), m.setListeningMode(address, ListeningMode.TRANSPARENCY))
    }

    @Test fun recoversFromRefusedConnections_withBackoff() = runTest {
        val transport = FakeTransport(failuresBeforeSuccess = 2)
        val m = manager(transport)
        connect(); runCurrent()
        assertTrue(m.accessories.value.single().channel is ChannelStatus.Failed)
        advanceTimeBy(2_001); runCurrent()   // second attempt fails
        advanceTimeBy(5_001); runCurrent()   // third attempt succeeds
        assertEquals(3, transport.attempts)
        assertEquals(ChannelStatus.Connected, m.accessories.value.single().channel)
    }

    @Test fun reconnectsAfterLinkDrop_whileStillConnected() = runTest {
        val transport = FakeTransport()
        val m = manager(transport)
        connect(); runCurrent()
        transport.connections.single().drop()
        runCurrent()
        assertEquals(1, transport.attempts)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, transport.attempts)
        assertEquals(ChannelStatus.Connected, m.accessories.value.single().channel)
    }

    @Test fun repeatedDrops_neverExhaustTheRetryBudget() = runTest {
        val transport = FakeTransport()
        val m = manager(transport)
        connect(); runCurrent()
        repeat(10) {
            transport.connections.last().drop(); runCurrent()
            advanceTimeBy(2_001); runCurrent()
        }
        assertEquals(11, transport.attempts)
        assertEquals(ChannelStatus.Connected, m.accessories.value.single().channel)
    }

    @Test fun weakFramesAreTrustedOnlyFromAnAddressAlreadyMatchedStrongly() = runTest {
        val m = manager(FakeTransport(support = AapTransport.OsSupport.UNSUPPORTED))
        connect("Laksh's AirPods Pro"); runCurrent()
        sightings.emit(sighting("4A:00:00:00:00:01", -80)); runCurrent()
        assertEquals("unknown weak advertiser is not attributed", null, m.accessories.value.single().ble)
        sightings.emit(sighting("4A:00:00:00:00:02", -55)); runCurrent()
        sightings.emit(sighting("4A:00:00:00:00:02", -80, statusHex = "23")); runCurrent()
        val ble = m.accessories.value.single().ble!!
        assertEquals(-80, ble.sighting.rssi)
        assertEquals(false, ble.sighting.status.rightInEar)
    }

    @Test fun givesUpAfterBoundedRetries() = runTest {
        val transport = FakeTransport(failuresBeforeSuccess = Int.MAX_VALUE)
        val m = manager(transport)
        connect(); runCurrent()
        advanceTimeBy(AccessoryManager.RETRY_BACKOFF_MILLIS.sum() + 1); runCurrent()
        assertEquals(AccessoryManager.RETRY_BACKOFF_MILLIS.size, transport.attempts)
        val status = m.accessories.value.single().channel as ChannelStatus.Failed
        assertEquals(null, status.nextRetryAtMillis)
    }

    @Test fun unsupportedOs_neverAttempts_andCapabilitiesExplainWhy() = runTest {
        val transport = FakeTransport(support = AapTransport.OsSupport.UNSUPPORTED)
        val m = manager(transport)
        connect(); runCurrent()
        assertEquals(0, transport.attempts)
        val state = m.accessories.value.single()
        assertTrue(state.channel is ChannelStatus.OsUnsupported)
        assertEquals(FeatureSupport.NeedsAccessoryChannel(ChannelUnavailableReason.OS_UNSUPPORTED), state.capabilities.support[Feature.NOISE_CONTROL])
        assertEquals(Outcome.Failure(FailureReason.NOT_CONNECTED), m.setListeningMode(address, ListeningMode.OFF))
    }

    @Test fun disconnect_tearsDownSession() = runTest {
        val transport = FakeTransport()
        val m = manager(transport)
        connect(); runCurrent()
        connected.value = emptyMap(); runCurrent()
        assertTrue(m.accessories.value.isEmpty())
        assertTrue(transport.connections.single().closed)
    }

    @Test fun userDisablingChannel_closesIt() = runTest {
        val transport = FakeTransport()
        val m = manager(transport)
        connect(); runCurrent()
        m.updateConfig { it.copy(channelEnabled = false) }; runCurrent()
        assertEquals(ChannelStatus.DisabledByUser, m.accessories.value.single().channel)
        assertTrue(transport.connections.single().closed)
    }
}
