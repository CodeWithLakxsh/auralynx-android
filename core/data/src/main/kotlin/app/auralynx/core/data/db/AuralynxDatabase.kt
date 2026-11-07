package app.auralynx.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    version = 1,
    exportSchema = true,
    entities = [
        BatterySampleEntity::class, ConnectionEventEntity::class, KnownDeviceEntity::class, RuleEntity::class,
        AutomationHistoryEntity::class, PlaceEntity::class, AudioProfileEntity::class, TranslationEntity::class,
        RecordingEntity::class, DiagnosticLogEntity::class, ListeningSessionEntity::class, GestureBindingEntity::class,
    ],
)
abstract class AuralynxDatabase : RoomDatabase() {
    abstract fun battery(): BatteryDao
    abstract fun connectionEvents(): ConnectionEventDao
    abstract fun knownDevices(): KnownDeviceDao
    abstract fun rules(): RuleDao
    abstract fun automationHistory(): AutomationHistoryDao
    abstract fun places(): PlaceDao
    abstract fun audioProfiles(): AudioProfileDao
    abstract fun translations(): TranslationDao
    abstract fun recordings(): RecordingDao
    abstract fun diagnostics(): DiagnosticLogDao
    abstract fun listeningSessions(): ListeningSessionDao
    abstract fun gestureBindings(): GestureBindingDao

    companion object {
        const val NAME = "auralynx.db"
    }
}
