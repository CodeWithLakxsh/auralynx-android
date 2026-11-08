package app.auralynx.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BatteryDao {
    @Insert suspend fun insert(sample: BatterySampleEntity): Long

    @Query("SELECT * FROM battery_samples WHERE deviceAddress = :address AND timestampMillis >= :sinceMillis ORDER BY timestampMillis")
    fun observe(address: String, sinceMillis: Long): Flow<List<BatterySampleEntity>>

    @Query("SELECT * FROM battery_samples WHERE deviceAddress = :address AND component = :component ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun latest(address: String, component: String): BatterySampleEntity?

    @Query("DELETE FROM battery_samples WHERE timestampMillis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long): Int

    @Query("DELETE FROM battery_samples") suspend fun clear()
}

@Dao
interface ConnectionEventDao {
    @Insert suspend fun insert(event: ConnectionEventEntity): Long

    @Query("SELECT * FROM connection_events ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<ConnectionEventEntity>>

    @Query("SELECT * FROM connection_events WHERE deviceAddress = :address ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeForDevice(address: String, limit: Int = 100): Flow<List<ConnectionEventEntity>>

    @Query("SELECT * FROM connection_events WHERE deviceAddress = :address ORDER BY timestampMillis DESC, id DESC LIMIT 1")
    suspend fun latest(address: String): ConnectionEventEntity?

    @Query("DELETE FROM connection_events WHERE timestampMillis < :beforeMillis") suspend fun deleteOlderThan(beforeMillis: Long): Int
    @Query("DELETE FROM connection_events") suspend fun clear()
}

@Dao
interface KnownDeviceDao {
    @Upsert suspend fun upsert(device: KnownDeviceEntity)
    @Query("SELECT * FROM known_devices WHERE address = :address") suspend fun get(address: String): KnownDeviceEntity?
    @Query("SELECT * FROM known_devices ORDER BY lastSeenMillis DESC") fun observeAll(): Flow<List<KnownDeviceEntity>>
    @Query("SELECT * FROM known_devices WHERE address = :address") fun observe(address: String): Flow<KnownDeviceEntity?>
    @Query("DELETE FROM known_devices") suspend fun clear()
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM automation_rules ORDER BY priority DESC, updatedAtMillis DESC") fun observeAll(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM automation_rules WHERE id = :id") suspend fun get(id: Long): RuleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(rule: RuleEntity): Long
    @Query("DELETE FROM automation_rules WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE automation_rules SET enabled = :enabled, updatedAtMillis = :now WHERE id = :id") suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)
    @Query("DELETE FROM automation_rules") suspend fun clear()
}

@Dao
interface AutomationHistoryDao {
    @Insert suspend fun insert(entry: AutomationHistoryEntity)
    @Query("SELECT * FROM automation_history ORDER BY timestampMillis DESC LIMIT :limit") fun observeRecent(limit: Int = 200): Flow<List<AutomationHistoryEntity>>
    @Query("DELETE FROM automation_history WHERE timestampMillis < :beforeMillis") suspend fun deleteOlderThan(beforeMillis: Long): Int
    @Query("DELETE FROM automation_history") suspend fun clear()
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY name") fun observeAll(): Flow<List<PlaceEntity>>
    @Query("SELECT * FROM places") suspend fun all(): List<PlaceEntity>
    @Insert suspend fun insert(place: PlaceEntity): Long
    @Delete suspend fun delete(place: PlaceEntity)
    @Query("DELETE FROM places") suspend fun clear()
}

@Dao
interface AudioProfileDao {
    @Query("SELECT * FROM audio_profiles ORDER BY builtInPreset IS NULL, id") fun observeAll(): Flow<List<AudioProfileEntity>>
    @Query("SELECT * FROM audio_profiles WHERE id = :id") suspend fun get(id: Long): AudioProfileEntity?
    @Query("SELECT COUNT(*) FROM audio_profiles") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(profile: AudioProfileEntity): Long
    @Insert suspend fun insertAll(profiles: List<AudioProfileEntity>)
    @Query("DELETE FROM audio_profiles WHERE id = :id AND builtInPreset IS NULL") suspend fun deleteCustom(id: Long)
}

@Dao
interface TranslationDao {
    @Insert suspend fun insert(entry: TranslationEntity): Long
    @Query("SELECT * FROM translations ORDER BY timestampMillis DESC LIMIT :limit") fun observeRecent(limit: Int = 100): Flow<List<TranslationEntity>>
    @Query("DELETE FROM translations") suspend fun clear()
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAtMillis DESC") fun observeAll(): Flow<List<RecordingEntity>>
    @Query("SELECT * FROM recordings WHERE id = :id") suspend fun get(id: Long): RecordingEntity?
    @Insert suspend fun insert(recording: RecordingEntity): Long
    @Upsert suspend fun update(recording: RecordingEntity)
    @Query("DELETE FROM recordings WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM recordings") suspend fun all(): List<RecordingEntity>
    @Query("DELETE FROM recordings") suspend fun clear()
}

@Dao
interface DiagnosticLogDao {
    @Insert suspend fun insertAll(entries: List<DiagnosticLogEntity>)
    @Query("SELECT * FROM diagnostic_log ORDER BY timestampMillis DESC LIMIT :limit") fun observeRecent(limit: Int = 500): Flow<List<DiagnosticLogEntity>>
    @Query("SELECT * FROM diagnostic_log ORDER BY timestampMillis") suspend fun all(): List<DiagnosticLogEntity>
    @Query("DELETE FROM diagnostic_log WHERE id NOT IN (SELECT id FROM diagnostic_log ORDER BY timestampMillis DESC LIMIT :keep)") suspend fun trim(keep: Int)
    @Query("DELETE FROM diagnostic_log") suspend fun clear()
}

@Dao
interface ListeningSessionDao {
    @Insert suspend fun insert(session: ListeningSessionEntity): Long
    @Query("UPDATE listening_sessions SET endMillis = :end, highVolumeSeconds = :highVolumeSeconds WHERE id = :id") suspend fun close(id: Long, end: Long, highVolumeSeconds: Long)
    @Query("SELECT * FROM listening_sessions WHERE deviceAddress = :address AND startMillis >= :since ORDER BY startMillis") fun observe(address: String, since: Long): Flow<List<ListeningSessionEntity>>
    @Query("UPDATE listening_sessions SET endMillis = :end WHERE endMillis IS NULL") suspend fun closeDangling(end: Long)
    @Query("DELETE FROM listening_sessions") suspend fun clear()
}

@Dao
interface GestureBindingDao {
    @Query("SELECT * FROM gesture_bindings") fun observeAll(): Flow<List<GestureBindingEntity>>
    @Query("SELECT * FROM gesture_bindings") suspend fun all(): List<GestureBindingEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(binding: GestureBindingEntity): Long
    @Query("DELETE FROM gesture_bindings WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM gesture_bindings") suspend fun clear()
}
