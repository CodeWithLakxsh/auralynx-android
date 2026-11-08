package app.auralynx.feature.translation

import app.auralynx.core.common.FailureReason
import app.auralynx.core.common.IoDispatcher
import app.auralynx.core.common.Outcome
import app.auralynx.core.data.security.SecretStore
import app.auralynx.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free cloud translation through the public MyMemory API (Translated srl,
 * https://mymemory.translated.net). No key, account, payment or server is needed.
 *
 * Opt-in and disclosed in the app: the recognised *text* (never audio) is sent over HTTPS.
 * Published free limits: 5,000 characters/day anonymously, 50,000/day if the user supplies a
 * contact email (stored encrypted, sent only to MyMemory as their `de` parameter).
 */
@Singleton
class MyMemoryEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TranslationEngine {
    override val id = "mymemory"
    override val displayName = "Cloud (MyMemory, free)"
    override val onDevice = false

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun availability(source: String, target: String): PairAvailability =
        if (settings.current().translationFreeCloudEnabled) PairAvailability.READY else PairAvailability.NOT_CONFIGURED

    override suspend fun translate(text: String, source: String, target: String): Outcome<String> = withContext(io) {
        if (!settings.current().translationFreeCloudEnabled) return@withContext Outcome.Failure(FailureReason.NOT_CONFIGURED)
        val parts = chunk(text.trim())
        if (parts.isEmpty()) return@withContext Outcome.Failure(FailureReason.UNKNOWN)
        val out = StringBuilder()
        for (part in parts) {
            when (val r = request(part, "$source|$target")) {
                is Outcome.Success -> { if (out.isNotEmpty()) out.append(' '); out.append(r.value.first) }
                is Outcome.Failure -> return@withContext r
            }
        }
        Outcome.Success(out.toString())
    }

    override suspend fun detectLanguage(text: String): String? = withContext(io) {
        if (!settings.current().translationFreeCloudEnabled) return@withContext null
        (request(text.take(MAX_CHARS), "autodetect|en") as? Outcome.Success)?.value?.second
    }

    /** Returns (translated text, detected language if reported). */
    private fun request(q: String, langPair: String): Outcome<Pair<String, String?>> = runCatching {
        val email = secrets.getString(SecretStore.TRANSLATION_CONTACT_EMAIL)?.takeIf { it.contains('@') }
        val url = buildString {
            append(ENDPOINT).append("?q=").append(URLEncoder.encode(q, "UTF-8"))
            append("&langpair=").append(URLEncoder.encode(langPair, "UTF-8"))
            if (email != null) append("&de=").append(URLEncoder.encode(email, "UTF-8"))
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000; conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Auralynx-Android")
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            parse(code, body)
        } finally {
            conn.disconnect()
        }
    }.getOrElse { Outcome.Failure(FailureReason.IO_ERROR, it) }

    internal fun parse(httpCode: Int, body: String): Outcome<Pair<String, String?>> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Outcome.Failure(FailureReason.IO_ERROR, IllegalStateException("HTTP $httpCode"))
        val status = root["responseStatus"]?.jsonPrimitive?.let { it.intOrNull ?: it.content.toIntOrNull() } ?: httpCode
        val quotaFinished = root["quotaFinished"]?.jsonPrimitive?.booleanOrNull == true
        val data = root["responseData"] as? JsonObject
        val translated = data?.get("translatedText")?.jsonPrimitive?.content?.let(::unescape)
        if (quotaFinished || status == 429) {
            return Outcome.Failure(FailureReason.IO_ERROR, IllegalStateException("MyMemory free daily limit reached"))
        }
        if (status != 200 || translated.isNullOrBlank() || translated.startsWith("MYMEMORY WARNING", ignoreCase = true)) {
            val details = root["responseDetails"]?.jsonPrimitive?.content
            return Outcome.Failure(FailureReason.IO_ERROR, IllegalStateException(details?.takeIf { it.isNotBlank() } ?: "MyMemory error $status"))
        }
        return Outcome.Success(translated to data["detectedLanguage"]?.jsonPrimitive?.content)
    }

    companion object {
        private const val ENDPOINT = "https://api.mymemory.translated.net/get"
        /** MyMemory accepts up to 500 bytes per query; stay safely below for multi-byte scripts. */
        const val MAX_CHARS = 450

        /** Splits on sentence boundaries, then on words, so no request exceeds [MAX_CHARS]. */
        fun chunk(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            if (text.encodeToByteArray().size <= 500) return listOf(text)
            val out = mutableListOf<String>()
            var current = StringBuilder()
            fun flush() { if (current.isNotBlank()) out += current.toString().trim(); current = StringBuilder() }
            for (word in text.split(Regex("(?<=[.!?。！？])\\s+|\\s+"))) {
                if ((current.length + word.length + 1) > MAX_CHARS / 2) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(word.take(MAX_CHARS / 2))
            }
            flush()
            return out
        }

        private fun unescape(s: String): String = s
            .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    }
}
