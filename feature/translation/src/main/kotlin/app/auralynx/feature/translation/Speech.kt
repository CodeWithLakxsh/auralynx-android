package app.auralynx.feature.translation

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

sealed interface SpeechEvent {
    data object Listening : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String, val detectedLanguage: String?) : SpeechEvent
    data class Error(val code: Int, val message: String) : SpeechEvent
}

/** Facts about speech recognition on this device, shown in the translation screen. */
data class RecognitionSupport(val available: Boolean, val onDeviceAvailable: Boolean, val languageDetection: Boolean)

/**
 * Wraps the platform [SpeechRecognizer]. Must be collected on the main thread (a platform
 * requirement). Prefers the on-device recognizer when the system provides one.
 */
class SpeechInput(private val context: Context) {

    fun support(): RecognitionSupport = RecognitionSupport(
        available = SpeechRecognizer.isRecognitionAvailable(context),
        onDeviceAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
        languageDetection = Build.VERSION.SDK_INT >= 34,
    )

    fun listen(languageTag: String?, detectLanguage: Boolean, preferOffline: Boolean): Flow<SpeechEvent> = callbackFlow {
        val s = support()
        val recognizer = if (preferOffline && s.onDeviceAvailable && Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SpeechEvent.Listening) }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { trySend(SpeechEvent.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val detected = if (Build.VERSION.SDK_INT >= 34) results?.getString(SpeechRecognizer.DETECTED_LANGUAGE) else null
                trySend(SpeechEvent.Final(text, detected))
                close()
            }
            override fun onError(error: Int) {
                trySend(SpeechEvent.Error(error, errorMessage(error)))
                close()
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            if (languageTag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            if (detectLanguage && Build.VERSION.SDK_INT >= 34) putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
        }
        recognizer.startListening(intent)
        awaitClose { runCatching { recognizer.cancel(); recognizer.destroy() } }
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognised"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service needs a network connection"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "This language is not supported by the speech service"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The language pack for this language is not installed"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy"
        else -> "Speech recognition error ($code)"
    }
}

/** Text-to-speech routed as media, so it plays through the connected headphones. */
class SpeechOutput(context: Context) {
    @Volatile private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }.apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    }

    val isReady: Boolean get() = ready

    fun isLanguageAvailable(tag: String): Boolean = ready && tts.isLanguageAvailable(Locale.forLanguageTag(tag)) >= TextToSpeech.LANG_AVAILABLE

    suspend fun speak(text: String, languageTag: String): Boolean {
        if (!ready) return false
        if (tts.setLanguage(Locale.forLanguageTag(languageTag)) < TextToSpeech.LANG_AVAILABLE) return false
        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(false) }
            })
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun shutdown() = tts.shutdown()
}
