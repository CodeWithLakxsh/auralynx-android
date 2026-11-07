package app.auralynx.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.auralynx.core.automation.Action
import app.auralynx.core.automation.Condition
import app.auralynx.core.automation.Rule
import app.auralynx.core.common.Clock
import app.auralynx.core.data.db.AuralynxDatabase
import app.auralynx.core.data.repo.AudioProfileRepository
import app.auralynx.core.data.repo.AutomationRepository
import app.auralynx.core.data.repo.BatteryRepository
import app.auralynx.core.data.repo.ConnectionEventType
import app.auralynx.core.data.repo.DeviceRepository
import app.auralynx.core.dsp.EqPreset
import app.auralynx.core.protocol.devices.AccessoryModel
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.BatteryReading
import app.auralynx.core.protocol.model.ChargingState
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.MeasurementSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/** Runs against a real (in-memory) SQLite database on the device or emulator. */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AuralynxDatabase
    private var now = 1_000_000L
    private val clock = Clock { now }
    private val addr = "AA:BB:CC:DD:EE:01"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AuralynxDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    private fun r(level: Int, src: MeasurementSource = MeasurementSource.ACCESSORY_CHANNEL) =
        BatteryReading(BatteryComponent.LEFT, level, ChargingState.DISCHARGING, src)

    @Test fun battery_deduplicatesUnchangedReadings_andKeepsHeartbeat() = runTest {
        val repo = BatteryRepository(db, clock)
        repo.record(addr, listOf(r(80)))
        now += 60_000; repo.record(addr, listOf(r(80)))           // unchanged, within heartbeat -> skipped
        now += 60_000; repo.record(addr, listOf(r(79)))           // changed -> stored
        now += BatteryRepository.HEARTBEAT_MILLIS; repo.record(addr, listOf(r(79))) // heartbeat -> stored
        val rows = repo.observe(addr, 0).first()
        assertEquals(listOf(80, 79, 79), rows.map { it.level })
    }

    @Test fun battery_lessPreciseSourceDoesNotOverwriteFreshPreciseValue() = runTest {
        val repo = BatteryRepository(db, clock)
        repo.record(addr, listOf(r(83, MeasurementSource.ACCESSORY_CHANNEL)))
        now += 10_000; repo.record(addr, listOf(r(80, MeasurementSource.BLE_ADVERTISEMENT)))
        assertEquals(listOf(83), repo.observe(addr, 0).first().map { it.level })
    }

    @Test fun rules_roundTripThroughDatabase() = runTest {
        val repo = AutomationRepository(db, clock)
        val rule = Rule(name = "Office", priority = 4,
            conditions = listOf(Condition.TimeWindow(540, 1020, setOf(DayOfWeek.MONDAY)), Condition.OnWifi("Lab")),
            actions = listOf(Action.SetListeningMode(ListeningMode.TRANSPARENCY)))
        val id = repo.save(rule)
        val loaded = repo.rule(id)!!
        assertEquals(rule.conditions, loaded.conditions)
        assertEquals(rule.actions, loaded.actions)
        repo.setEnabled(id, false)
        assertEquals(false, repo.rules.first().single().enabled)
    }

    @Test fun devices_trackEventsAndObservedFeatures() = runTest {
        val repo = DeviceRepository(db, clock)
        repo.recordSeen(addr, "AirPods Pro", AccessoryModel.AIRPODS_PRO)
        repo.recordEvent(addr, "AirPods Pro", ConnectionEventType.CONNECTED)
        now += 1000
        repo.recordEvent(addr, "AirPods Pro", ConnectionEventType.UNEXPECTED_DISCONNECT, "worn")
        repo.addObservedFeatures(addr, setOf(Feature.BATTERY, Feature.EAR_DETECTION))
        val d = repo.knownDevices.first().single()
        assertNotNull(d.lastConnectedMillis)
        assertEquals(now, d.lastDisconnectedMillis)
        assertEquals(setOf(Feature.BATTERY, Feature.EAR_DETECTION), DeviceRepository.observedFeatures(d))
        assertEquals(2, repo.recentEvents.first().size)
    }

    @Test fun audioProfiles_seedBuiltInsOnce() = runTest {
        val repo = AudioProfileRepository(db)
        repo.ensureBuiltIns(); repo.ensureBuiltIns()
        val all = repo.profiles.first()
        assertEquals(EqPreset.entries.size, all.size)
        assertTrue(all.all { it.gainsDb.size == 10 })
    }

    @Test fun clearAllTables_removesEverything() = runTest {
        BatteryRepository(db, clock).record(addr, listOf(r(50)))
        DeviceRepository(db, clock).recordSeen(addr, "x", null)
        db.clearAllTables()
        assertTrue(db.battery().observe(addr, 0).first().isEmpty())
        assertTrue(db.knownDevices().observeAll().first().isEmpty())
    }
}
