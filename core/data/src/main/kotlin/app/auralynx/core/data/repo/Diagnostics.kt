package app.auralynx.core.data.repo

import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.common.Clock
import app.auralynx.core.common.Diag
import app.auralynx.core.common.Redaction
import app.auralynx.core.data.db.AuralynxDatabase
import app.auralynx.core.data.db.DiagnosticLogEntity
import app.auralynx.core.data.security.SecretStore
import app.auralynx.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists redacted diagnostic log entries locally when the user enables diagnostic logging.
 * Nothing is ever uploaded; [buildExport] produces a text report only on explicit user action.
 */
@Singleton
class DiagnosticsRepository @Inject constructor(
    private val db: AuralynxDatabase,
    @ApplicationScope private val scope: CoroutineScope,
) : Diag.Sink {
    private val queue = Channel<Diag.Entry>(capacity = 512, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    val recent: Flow<List<DiagnosticLogEntity>> = db.diagnostics().observeRecent()

    init {
        scope.launch {
            val batch = mutableListOf<DiagnosticLogEntity>()
            for (entry in queue) {
                batch += DiagnosticLogEntity(0, entry.timeMillis, entry.level.name, entry.tag, entry.message)
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    batch += DiagnosticLogEntity(0, next.timeMillis, next.level.name, next.tag, next.message)
                }
                db.diagnostics().insertAll(batch.toList())
                batch.clear()
                db.diagnostics().trim(MAX_ENTRIES)
            }
        }
    }

    override fun write(entry: Diag.Entry) {
        queue.trySend(entry)
    }

    suspend fun clear() = db.diagnostics().clear()

    /** Plain-text report. Identifiers are redacted again as a defence in depth. */
    suspend fun buildExport(header: Map<String, String>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine("Auralynx diagnostic export")
            appendLine("Generated: ${fmt.format(Date())}")
            appendLine("Identifiers (Bluetooth addresses, serial numbers) are redacted.")
            header.forEach { (k, v) -> appendLine("$k: ${Redaction.text(v)}") }
            appendLine()
            db.diagnostics().all().forEach { e ->
                appendLine("${fmt.format(Date(e.timestampMillis))} ${e.level.first()} ${e.tag}: ${Redaction.text(e.message)}")
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 5000
    }
}

/** Retention clean-up and "delete all my data". */
@Singleton
class DataMaintenance @Inject constructor(
    private val db: AuralynxDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val clock: Clock,
) {
    suspend fun applyRetention() {
        val days = settings.current().retentionDays
        val cutoff = clock.nowMillis() - days * 86_400_000L
        db.battery().deleteOlderThan(cutoff)
        db.connectionEvents().deleteOlderThan(cutoff)
        db.automationHistory().deleteOlderThan(cutoff)
        db.diagnostics().trim(DiagnosticsRepository.MAX_ENTRIES)
        db.listeningSessions().closeDangling(clock.nowMillis())
    }

    /** Deletes every piece of data Auralynx stored on this device, including recordings and keys. */
    suspend fun deleteEverything() {
        db.recordings().all().forEach { runCatching { java.io.File(it.filePath).delete() } }
        db.clearAllTables()
        secrets.clear()
        settings.clear()
    }
}
