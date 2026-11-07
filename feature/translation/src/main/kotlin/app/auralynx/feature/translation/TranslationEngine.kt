package app.auralynx.feature.translation

import app.auralynx.core.common.Outcome

/** Availability of a language pair on an engine. */
enum class PairAvailability { READY, NEEDS_DOWNLOAD, DOWNLOADING, UNSUPPORTED, ENGINE_UNAVAILABLE, NOT_CONFIGURED }

/** A translation backend. Engines never fabricate output: on any failure they return Outcome.Failure. */
interface TranslationEngine {
    val id: String
    val displayName: String
    /** True when text never leaves the device. */
    val onDevice: Boolean

    suspend fun availability(source: String, target: String): PairAvailability
    suspend fun translate(text: String, source: String, target: String): Outcome<String>

    /** Best-effort text language identification; null when the engine cannot detect languages. */
    suspend fun detectLanguage(text: String): String? = null
}

/** Languages offered in the picker (BCP-47 tags). Availability is still checked per engine. */
val SUPPORTED_LANGUAGES: List<Pair<String, String>> = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "tr" to "Turkish", "ru" to "Russian",
    "uk" to "Ukrainian", "ar" to "Arabic", "hi" to "Hindi", "bn" to "Bengali", "ja" to "Japanese",
    "ko" to "Korean", "zh" to "Chinese", "id" to "Indonesian", "vi" to "Vietnamese", "th" to "Thai",
)
