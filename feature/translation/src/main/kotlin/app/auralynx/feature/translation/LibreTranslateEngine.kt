package app.auralynx.feature.translation

import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.IoDispatcher
import app.auralynx.core.common.Outcome
import app.auralynx.core.data.security.SecretStore
import app.auralynx.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional cloud translation through a **user-configured** LibreTranslate server
 * (https://github.com/LibreTranslate/LibreTranslate, self-hostable, AGPL-3.0 server).
 *
 * Disabled by default. No server address or API key ships with Auralynx; the user enters their
 * own, and the key is stored encrypted with the Android Keystore. Text is sent over HTTPS only.
 */
@Singleton
class LibreTranslateEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TranslationEngine {
    override val id = "libretranslate"
    override val displayName = "Cloud (your LibreTranslate server)"
    override val onDevice = false

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class TranslateRequest(val q: String, val source: String, val target: String, val format: String = "text", val api_key: String? = null)
    @Serializable private data class TranslateResponse(val translatedText: String? = null, val error: String? = null)
    @Serializable private data class DetectRequest(val q: String, val api_key: String? = null)
    @Serializable private data class Detection(val language: String, val confidence: Double = 0.0)

    private suspend fun baseUrl(): String? {
        val s = settings.current()
        if (!s.translationCloudEnabled) return null
        return s.translationCloudUrl?.trim()?.trimEnd('/')?.takeIf { isAllowedUrl(it) }
    }

    override suspend fun availability(source: String, target: String): PairAvailability =
        if (baseUrl() == null) PairAvailability.NOT_CONFIGURED else PairAvailability.READY

    override suspend fun translate(text: String, source: String, target: String): Outcome<String> = withContext(io) {
        val base = baseUrl() ?: return@withContext Outcome.Failure(FailureReason.NOT_CONFIGURED)
        val body = json.encodeToString(TranslateRequest.serializer(), TranslateRequest(text, source, target, api_key = secrets.getString(SecretStore.TRANSLATION_API_KEY)))
        runCatching { post("$base/translate", body) }.fold(
            onSuccess = { (code, response) ->
                val parsed = runCatching { json.decodeFromString(TranslateResponse.serializer(), response) }.getOrNull()
                val translated = parsed?.translatedText
                if (code in 200..299 && !translated.isNullOrBlank()) Outcome.Success(translated)
                else Outcome.Failure(FailureReason.IO_ERROR, IllegalStateException(parsed?.error ?: "HTTP $code"))
            },
            onFailure = { Outcome.Failure(FailureReason.IO_ERROR, it) },
        )
    }

    override suspend fun detectLanguage(text: String): String? = withContext(io) {
        val base = baseUrl() ?: return@withContext null
        val body = json.encodeToString(DetectRequest.serializer(), DetectRequest(text, secrets.getString(SecretStore.TRANSLATION_API_KEY)))
        runCatching {
            val (code, response) = post("$base/detect", body)
            if (code !in 200..299) return@runCatching null
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Detection.serializer()), response).maxByOrNull { it.confidence }?.language
        }.getOrNull()
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000; conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Auralynx-Android")
            conn.outputStream.use { it.write(body.encodeToByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** HTTPS only. Cleartext is also blocked app-wide by the network security config. */
        fun isAllowedUrl(url: String): Boolean = runCatching {
            val u = URL(url)
            u.protocol == "https" && !u.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
