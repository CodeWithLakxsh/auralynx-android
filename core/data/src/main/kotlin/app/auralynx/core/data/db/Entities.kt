package app.auralynx.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One battery measurement exactly as received. Estimates are never stored here. */
@Entity(tableName = "battery_samples", indices = [Index("deviceAddress", "timestampMillis")])
data class BatterySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val timestampMillis: Long,
    /** BatteryComponent name. */
    val component: String,
    val level: Int,
    /** ChargingState name. */
    val charging: String,
    /** MeasurementSource name. */
    val source: String,
)

@Entity(tableName = "connection_events", indices = [Index("timestampMillis")])
data class ConnectionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val deviceName: String?,
    val timestampMillis: Long,
    val type: String,
    val detail: String? = null,
)

@Entity(tableName = "known_devices")
data class KnownDeviceEntity(
    @PrimaryKey val address: String,
    val name: String?,
    /** AccessoryModel name. */
    val model: String?,
    val modelNumber: String? = null,
    val firmware: String? = null,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val lastConnectedMillis: Long? = null,
    val lastDisconnectedMillis: Long? = null,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastLocationAccuracyMeters: Float? = null,
    val lastLocationMillis: Long? = null,
    /** Comma-separated Feature names observed working on this accessory. */
    val observedFeatures: String = "",
)

@Entity(tableName = "automation_rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val conditionsJson: String,
    val actionsJson: String,
    val updatedAtMillis: Long,
)

@Entity(tableName = "automation_history", indices = [Index("timestampMillis")])
data class AutomationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val ruleId: Long?,
    val ruleName: String,
    val action: String,
    /** APPLIED, SKIPPED_OVERRIDE, FAILED_NOT_CONNECTED, FAILED_UNSUPPORTED, CONFLICT */
    val outcome: String,
    val detail: String? = null,
)

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
)

@Entity(tableName = "audio_profiles")
data class AudioProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Ten comma-separated band gains in dB. */
    val gainsCsv: String,
    val preampDb: Double,
    /** SpatialMode name for the app-local player. */
    val spatialMode: String,
    val builtInPreset: String? = null,
)

@Entity(tableName = "translations", indices = [Index("timestampMillis")])
data class TranslationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceText: String,
    val translatedText: String,
    val engine: String,
)

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Absolute path inside app-private storage. */
    val filePath: String,
    val createdAtMillis: Long,
    val durationMillis: Long,
    val sampleRate: Int,
    val inputDevice: String?,
    val noiseReduced: Boolean = false,
    val voiceEnhanced: Boolean = false,
    val transcript: String? = null,
)

@Entity(tableName = "diagnostic_log", indices = [Index("timestampMillis")])
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val level: String,
    val tag: String,
    /** Already redacted by Diag before it reaches the database. */
    val message: String,
)

/** Continuous periods with at least one bud in ear (from ear detection) or connected (headphones). */
@Entity(tableName = "listening_sessions", indices = [Index("deviceAddress", "startMillis")])
data class ListeningSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val startMillis: Long,
    val endMillis: Long?,
    /** Seconds within the session where media volume was >= 80 % of maximum. */
    val highVolumeSeconds: Long = 0,
)

/** Maps an accessory stem press to an Auralynx action. */
@Entity(tableName = "gesture_bindings")
data class GestureBindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** PressType name. */
    val pressType: String,
    /** Bud name or null for either bud. */
    val bud: String?,
    /** GestureAction name. */
    val action: String,
    val argument: String? = null,
)
