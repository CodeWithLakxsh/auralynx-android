package app.auralynx.feature.translation

import android.app.PendingIntent
import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponse
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import androidx.annotation.RequiresApi
import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.IoDispatcher
import app.auralynx.core.common.Outcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * On-device translation through the platform [TranslationManager] (Android 12+).
 *
 * The models belong to the system translation service (for example Android System Intelligence on
 * Pixel devices); Auralynx bundles none. Language packs are installed through the system's own
 * settings screen ([downloadIntent]). Devices without a translation service report
 * [PairAvailability.ENGINE_UNAVAILABLE].
 */
@Singleton
class SystemTranslationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TranslationEngine {
    override val id = "system"
    override val displayName = "On-device (system translation service)"
    override val onDevice = true

    private val executor = Executors.newSingleThreadExecutor()

    private val manager: TranslationManager?
        get() = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(TranslationManager::class.java) else null

    override suspend fun availability(source: String, target: String): PairAvailability = withContext(io) {
        if (Build.VERSION.SDK_INT < 31) return@withContext PairAvailability.ENGINE_UNAVAILABLE
        val m = manager ?: return@withContext PairAvailability.ENGINE_UNAVAILABLE
        val caps = runCatching { m.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT) }
            .getOrNull() ?: return@withContext PairAvailability.ENGINE_UNAVAILABLE
        if (caps.isEmpty()) return@withContext PairAvailability.ENGINE_UNAVAILABLE
        val match = caps.firstOrNull {
            it.sourceSpec.locale.language == ULocale(source).language && it.targetSpec.locale.language == ULocale(target).language
        } ?: return@withContext PairAvailability.UNSUPPORTED
        when (match.state) {
            TranslationCapability.STATE_ON_DEVICE -> PairAvailability.READY
            TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD -> PairAvailability.NEEDS_DOWNLOAD
            TranslationCapability.STATE_DOWNLOADING -> PairAvailability.DOWNLOADING
            else -> PairAvailability.UNSUPPORTED
        }
    }

    /** System screen where the user installs translation language packs. */
    fun downloadIntent(): PendingIntent? = if (Build.VERSION.SDK_INT >= 31) runCatching { manager?.onDeviceTranslationSettingsActivityIntent }.getOrNull() else null

    override suspend fun translate(text: String, source: String, target: String): Outcome<String> {
        if (Build.VERSION.SDK_INT < 31) return Outcome.Failure(FailureReason.UNSUPPORTED_BY_ANDROID)
        if (text.isBlank()) return Outcome.Failure(FailureReason.UNKNOWN)
        return translateApi31(text, source, target)
    }

    @RequiresApi(31)
    private suspend fun translateApi31(text: String, source: String, target: String): Outcome<String> {
        val m = manager ?: return Outcome.Failure(FailureReason.UNSUPPORTED_BY_ANDROID)
        val ctx = TranslationContext.Builder(
            TranslationSpec(ULocale(source), TranslationSpec.DATA_FORMAT_TEXT),
            TranslationSpec(ULocale(target), TranslationSpec.DATA_FORMAT_TEXT),
        ).build()
        val translator: Translator = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont -> m.createOnDeviceTranslator(ctx, executor) { t -> cont.resume(t) } }
        } ?: return Outcome.Failure(FailureReason.UNSUPPORTED_BY_ANDROID)
        try {
            val request = TranslationRequest.Builder()
                .setTranslationRequestValues(listOf(TranslationRequestValue.forText(text)))
                .build()
            val response: TranslationResponse = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    translator.translate(request, signal, executor) { r -> if (r.isFinalResponse && cont.isActive) cont.resume(r) }
                }
            } ?: return Outcome.Failure(FailureReason.TIMEOUT)
            if (response.translationStatus != TranslationResponse.TRANSLATION_STATUS_SUCCESS) return Outcome.Failure(FailureReason.UNSUPPORTED_BY_ANDROID)
            val value = response.translationResponseValues.get(0)
            val out = value?.text?.toString()
            return if (value?.statusCode == android.view.translation.TranslationResponseValue.STATUS_SUCCESS && !out.isNullOrBlank()) {
                Outcome.Success(out)
            } else {
                Outcome.Failure(FailureReason.UNKNOWN)
            }
        } finally {
            translator.destroy()
        }
    }
}
