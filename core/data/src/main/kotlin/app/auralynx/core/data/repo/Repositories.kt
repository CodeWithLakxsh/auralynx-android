package app.auralynx.core.data.repo

import app.auralynx.core.automation.Rule
import app.auralynx.core.automation.RuleCodec
import app.auralynx.core.common.Clock
import app.auralynx.core.data.db.AudioProfileEntity
import app.auralynx.core.data.db.AuralynxDatabase
import app.auralynx.core.data.db.AutomationHistoryEntity
import app.auralynx.core.data.db.BatterySampleEntity
import app.auralynx.core.data.db.ConnectionEventEntity
import app.auralynx.core.data.db.GestureBindingEntity
import app.auralynx.core.data.db.KnownDeviceEntity
import app.auralynx.core.data.db.ListeningSessionEntity
import app.auralynx.core.data.db.PlaceEntity
import app.auralynx.core.data.db.RecordingEntity
import app.auralynx.core.data.db.RuleEntity
import app.auralynx.core.data.db.TranslationEntity
import app.auralynx.core.dsp.EqPreset
import app.auralynx.core.dsp.SpatialMode
import app.auralynx.core.protocol.devices.AccessoryModel
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.model.BatteryReading
import app.auralynx.core.protocol.model.ChargingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionEventType { CONNECTED, DISCONNECTED, UNEXPECTED_DISCONNECT, CHANNEL_OPENED, CHANNEL_FAILED, CHANNEL_CLOSED }

@Singleton
class DeviceRepository @Inject constructor(private val db: AuralynxDatabase, private val clock: Clock) {
    val knownDevices: Flow<List<KnownDeviceEntity>> = db.knownDevices().observeAll()
    val recentEvents: Flow<List<ConnectionEventEntity>> = db.connectionEvents().observeRecent()

    fun device(address: String): Flow<KnownDeviceEntity?> = db.knownDevices().observe(address)
    fun events(address: String): Flow<List<ConnectionEventEntity>> = db.connectionEvents().observeForDevice(address)

    suspend fun recordSeen(address: String, name: String?, model: AccessoryModel?) {
        val now = clock.nowMillis()
        val existing = db.knownDevices().get(address)
        db.knownDevices().upsert(
            existing?.copy(
                name = name ?: existing.name,
                model = model?.takeIf { it != AccessoryModel.UNKNOWN }?.name ?: existing.model,
                lastSeenMillis = now,
            ) ?: KnownDeviceEntity(address, name, model?.name, firstSeenMillis = now, lastSeenMillis = now),
        )
    }

    suspend fun recordEvent(address: String, name: String?, type: ConnectionEventType, detail: String? = null) {
        val now = clock.nowMillis()
        db.connectionEvents().insert(ConnectionEventEntity(0, address, name, now, type.name, detail))
        val existing = db.knownDevices().get(address) ?: KnownDeviceEntity(address, name, null, firstSeenMillis = now, lastSeenMillis = now)
        db.knownDevices().upsert(
            when (type) {
                ConnectionEventType.CONNECTED -> existing.copy(lastConnectedMillis = now, lastSeenMillis = now)
                ConnectionEventType.DISCONNECTED, ConnectionEventType.UNEXPECTED_DISCONNECT ->
                    existing.copy(lastDisconnectedMillis = now, lastSeenMillis = now)
                else -> existing.copy(lastSeenMillis = now)
            },
        )
    }

    suspend fun recordDeviceInfo(address: String, modelNumber: String, firmware: String, model: AccessoryModel?) {
        val existing = db.knownDevices().get(address) ?: return
        db.knownDevices().upsert(existing.copy(modelNumber = modelNumber, firmware = firmware, model = model?.name ?: existing.model))
    }

    suspend fun recordLocation(address: String, latitude: Double, longitude: Double, accuracy: Float, timeMillis: Long) {
        val existing = db.knownDevices().get(address) ?: return
        db.knownDevices().upsert(existing.copy(lastLatitude = latitude, lastLongitude = longitude, lastLocationAccuracyMeters = accuracy, lastLocationMillis = timeMillis))
    }

    suspend fun addObservedFeatures(address: String, features: Set<Feature>) {
        val existing = db.knownDevices().get(address) ?: return
        val merged = observedFeatures(existing) + features
        if (merged != observedFeatures(existing)) db.knownDevices().upsert(existing.copy(observedFeatures = merged.joinToString(",") { it.name }))
    }

    companion object {
        fun observedFeatures(e: KnownDeviceEntity): Set<Feature> =
            e.observedFeatures.split(',').mapNotNull { n -> Feature.entries.firstOrNull { it.name == n } }.toSet()
    }
}

@Singleton
class BatteryRepository @Inject constructor(private val db: AuralynxDatabase, private val clock: Clock) {
    private val lock = Mutex()

    /**
     * Records readings, skipping duplicates: a new row is written when the level or charging state
     * changes, or every [HEARTBEAT_MILLIS] so the history shows that the value was stable.
     */
    suspend fun record(address: String, readings: List<BatteryReading>) = lock.withLock {
        val now = clock.nowMillis()
        for (r in readings) {
            val last = db.battery().latest(address, r.component.name)
            val changed = last == null || last.level != r.level || last.charging != r.charging.name
            val stale = last == null || now - last.timestampMillis >= HEARTBEAT_MILLIS
            // Prefer precise sources: never let a 10 % reading overwrite a fresh 1 % reading.
            val lessPrecise = last != null && now - last.timestampMillis < HEARTBEAT_MILLIS &&
                precision(last.source) < r.source.precisionPercent
            if ((changed || stale) && !lessPrecise) {
                db.battery().insert(BatterySampleEntity(0, address, now, r.component.name, r.level, r.charging.name, r.source.name))
            }
        }
    }

    fun observe(address: String, sinceMillis: Long): Flow<List<BatterySampleEntity>> = db.battery().observe(address, sinceMillis)

    suspend fun latest(address: String, component: String): BatterySampleEntity? = db.battery().latest(address, component)

    private fun precision(source: String) =
        app.auralynx.core.protocol.model.MeasurementSource.entries.firstOrNull { it.name == source }?.precisionPercent ?: 10

    companion object {
        const val HEARTBEAT_MILLIS = 5 * 60_000L

        fun toPoints(samples: List<BatterySampleEntity>, component: String): List<BatteryPoint> = samples
            .filter { it.component == component }
            .map { BatteryPoint(it.timestampMillis, it.level, runCatching { ChargingState.valueOf(it.charging) }.getOrDefault(ChargingState.UNKNOWN)) }
    }
}

@Singleton
class AutomationRepository @Inject constructor(private val db: AuralynxDatabase, private val clock: Clock) {
    val rules: Flow<List<Rule>> = db.rules().observeAll().map { list -> list.mapNotNull { it.toRuleOrNull() } }
    val history: Flow<List<AutomationHistoryEntity>> = db.automationHistory().observeRecent()
    val places: Flow<List<PlaceEntity>> = db.places().observeAll()

    suspend fun rule(id: Long): Rule? = db.rules().get(id)?.toRuleOrNull()

    suspend fun save(rule: Rule): Long = db.rules().upsert(
        RuleEntity(rule.id, rule.name.trim(), rule.enabled, rule.priority,
            RuleCodec.encodeConditions(rule.conditions), RuleCodec.encodeActions(rule.actions), clock.nowMillis()),
    )

    suspend fun delete(id: Long) = db.rules().delete(id)
    suspend fun setEnabled(id: Long, enabled: Boolean) = db.rules().setEnabled(id, enabled, clock.nowMillis())

    suspend fun log(ruleId: Long?, ruleName: String, action: String, outcome: String, detail: String? = null) =
        db.automationHistory().insert(AutomationHistoryEntity(0, clock.nowMillis(), ruleId, ruleName, action, outcome, detail))

    suspend fun allPlaces(): List<PlaceEntity> = db.places().all()
    suspend fun addPlace(place: PlaceEntity): Long = db.places().insert(place)
    suspend fun deletePlace(place: PlaceEntity) = db.places().delete(place)

    private fun RuleEntity.toRuleOrNull(): Rule? = runCatching {
        Rule(id, name, enabled, priority, RuleCodec.decodeConditions(conditionsJson), RuleCodec.decodeActions(actionsJson), updatedAtMillis)
    }.getOrNull()
}

/** EQ/spatial profile as used by the audio module. */
data class AudioProfile(
    val id: Long,
    val name: String,
    val gainsDb: List<Double>,
    val preampDb: Double,
    val spatialMode: SpatialMode,
    val builtIn: EqPreset?,
)

@Singleton
class AudioProfileRepository @Inject constructor(private val db: AuralynxDatabase) {
    val profiles: Flow<List<AudioProfile>> = db.audioProfiles().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun ensureBuiltIns() {
        if (db.audioProfiles().count() > 0) return
        db.audioProfiles().insertAll(EqPreset.entries.map {
            AudioProfileEntity(0, it.displayName, it.gainsDb.joinToString(","), it.preampDb, SpatialMode.OFF.name, it.name)
        })
    }

    suspend fun get(id: Long): AudioProfile? = db.audioProfiles().get(id)?.toDomain()

    suspend fun saveCustom(profile: AudioProfile): Long = db.audioProfiles().upsert(
        AudioProfileEntity(if (profile.builtIn != null) 0 else profile.id, profile.name.trim(),
            profile.gainsDb.joinToString(","), profile.preampDb, profile.spatialMode.name, null),
    )

    suspend fun deleteCustom(id: Long) = db.audioProfiles().deleteCustom(id)

    private fun AudioProfileEntity.toDomain() = AudioProfile(
        id, name, gainsCsv.split(',').mapNotNull { it.trim().toDoubleOrNull() }.let { if (it.size == 10) it else List(10) { 0.0 } },
        preampDb, runCatching { SpatialMode.valueOf(spatialMode) }.getOrDefault(SpatialMode.OFF),
        builtInPreset?.let { runCatching { EqPreset.valueOf(it) }.getOrNull() },
    )
}

@Singleton
class TranslationHistoryRepository @Inject constructor(private val db: AuralynxDatabase, private val clock: Clock) {
    val recent: Flow<List<TranslationEntity>> = db.translations().observeRecent()
    suspend fun add(source: String, target: String, sourceText: String, translated: String, engine: String) =
        db.translations().insert(TranslationEntity(0, clock.nowMillis(), source, target, sourceText, translated, engine))
    suspend fun clear() = db.translations().clear()
}

@Singleton
class RecordingRepository @Inject constructor(private val db: AuralynxDatabase) {
    val recordings: Flow<List<RecordingEntity>> = db.recordings().observeAll()
    suspend fun add(r: RecordingEntity): Long = db.recordings().insert(r)
    suspend fun update(r: RecordingEntity) = db.recordings().update(r)
    suspend fun get(id: Long) = db.recordings().get(id)
    suspend fun delete(r: RecordingEntity) {
        runCatching { java.io.File(r.filePath).delete() }
        db.recordings().delete(r.id)
    }
}

@Singleton
class ListeningRepository @Inject constructor(private val db: AuralynxDatabase) {
    fun sessions(address: String, since: Long): Flow<List<ListeningSessionEntity>> = db.listeningSessions().observe(address, since)
    suspend fun start(address: String, now: Long): Long = db.listeningSessions().insert(ListeningSessionEntity(0, address, now, null))
    suspend fun stop(id: Long, now: Long, highVolumeSeconds: Long) = db.listeningSessions().close(id, now, highVolumeSeconds)
    suspend fun closeDangling(now: Long) = db.listeningSessions().closeDangling(now)
}

/** Actions a stem press can trigger. Only presses the accessory actually reports can be bound. */
enum class GestureAction { PLAY_PAUSE, NEXT_TRACK, PREVIOUS_TRACK, CYCLE_LISTENING_MODE, APPLY_AUDIO_PROFILE, TOGGLE_PROFILE, LAUNCH_APP, START_RECORDING }

@Singleton
class GestureRepository @Inject constructor(private val db: AuralynxDatabase) {
    val bindings: Flow<List<GestureBindingEntity>> = db.gestureBindings().observeAll()
    suspend fun all() = db.gestureBindings().all()
    suspend fun save(binding: GestureBindingEntity) = db.gestureBindings().upsert(binding)
    suspend fun delete(id: Long) = db.gestureBindings().delete(id)
}
