package app.auralynx.ui.translation

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.auralynx.core.common.Outcome
import app.auralynx.core.data.security.SecretStore
import app.auralynx.core.data.settings.SettingsRepository
import app.auralynx.core.data.repo.TranslationHistoryRepository
import app.auralynx.core.designsystem.AuralynxCard
import app.auralynx.core.designsystem.InfoNote
import app.auralynx.core.designsystem.SectionHeader
import app.auralynx.core.designsystem.SettingSwitch
import app.auralynx.core.designsystem.StatusKind
import app.auralynx.core.designsystem.StatusPill
import app.auralynx.core.designsystem.Tokens
import app.auralynx.feature.translation.LibreTranslateEngine
import app.auralynx.feature.translation.PairAvailability
import app.auralynx.feature.translation.SUPPORTED_LANGUAGES
import app.auralynx.feature.translation.SpeechEvent
import app.auralynx.feature.translation.SpeechInput
import app.auralynx.feature.translation.SpeechOutput
import app.auralynx.feature.translation.TranslationService
import app.auralynx.ui.common.formatTime
import app.auralynx.ui.device.Dropdown
import app.auralynx.ui.device.ScreenColumn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Turn(val heard: String, val translated: String?, val error: String?, val engine: String?)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: TranslationService,
    private val settingsRepo: SettingsRepository,
    private val secrets: SecretStore,
    history: TranslationHistoryRepository,
) : ViewModel() {
    private val speech = SpeechInput(context)
    private val tts = SpeechOutput(context)
    val support = speech.support()
    val source = MutableStateFlow("en")
    val target = MutableStateFlow("es")
    val availability = MutableStateFlow<PairAvailability?>(null)
    val engineName = MutableStateFlow<String?>(null)
    val partial = MutableStateFlow("")
    val listening = MutableStateFlow(false)
    val turns = MutableStateFlow<List<Turn>>(emptyList())
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history = history.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val downloadIntent get() = service.system.downloadIntent()
    private var job: Job? = null

    fun refresh() = viewModelScope.launch {
        val (engine, a) = service.pickEngine(source.value, target.value)
        availability.value = if (engine != null) PairAvailability.READY else a
        engineName.value = engine?.displayName
    }

    fun swap() { val s = source.value; source.value = target.value; target.value = s; refresh() }

    /** One conversation turn: listen in [from], translate to [to], speak the result. */
    fun listen(reverse: Boolean) {
        val from = if (reverse) target.value else source.value
        val to = if (reverse) source.value else target.value
        job?.cancel()
        job = viewModelScope.launch {
            listening.value = true; partial.value = ""
            speech.listen(from, detectLanguage = false, preferOffline = true).collect { e ->
                when (e) {
                    SpeechEvent.Listening -> Unit
                    is SpeechEvent.Partial -> partial.value = e.text
                    is SpeechEvent.Final -> { listening.value = false; if (e.text.isNotBlank()) translate(e.text, from, to) }
                    is SpeechEvent.Error -> { listening.value = false; turns.value = turns.value + Turn(partial.value, null, e.message, null) }
                }
            }
            listening.value = false
        }
    }

    fun translateTyped(text: String) = viewModelScope.launch { translate(text, source.value, target.value) }

    private suspend fun translate(text: String, from: String, to: String) {
        when (val r = service.translate(text, from, to)) {
            is Outcome.Success -> {
                turns.value = turns.value + Turn(text, r.value.text, null, r.value.engine.displayName)
                if (!tts.speak(r.value.text, to)) turns.value = turns.value.dropLast(1) + Turn(text, r.value.text, "No text-to-speech voice for this language", r.value.engine.displayName)
            }
            is Outcome.Failure -> turns.value = turns.value + Turn(text, null, "Translation unavailable: no on-device language pack and no cloud server configured", null)
        }
    }

    fun stop() { job?.cancel(); listening.value = false }

    fun configureCloud(enabled: Boolean, url: String, apiKey: String) = viewModelScope.launch {
        settingsRepo.update { it.copy(translationCloudEnabled = enabled && LibreTranslateEngine.isAllowedUrl(url), translationCloudUrl = url.ifBlank { null }) }
        if (apiKey.isNotBlank()) secrets.putString(SecretStore.TRANSLATION_API_KEY, apiKey)
        refresh()
    }

    val contactEmail = MutableStateFlow(secrets.getString(SecretStore.TRANSLATION_CONTACT_EMAIL).orEmpty())

    fun setFreeCloud(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(translationFreeCloudEnabled = enabled) }
        refresh()
    }

    fun saveContactEmail(email: String) {
        val e = email.trim()
        if (e.isEmpty()) secrets.remove(SecretStore.TRANSLATION_CONTACT_EMAIL)
        else if (android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) secrets.putString(SecretStore.TRANSLATION_CONTACT_EMAIL, e)
        contactEmail.value = e
    }

    fun setSaveHistory(on: Boolean) = viewModelScope.launch { settingsRepo.update { it.copy(translationSaveHistory = on) } }

    override fun onCleared() { tts.shutdown() }
}

@Composable
fun TranslationScreen(vm: TranslationViewModel) {
    val src by vm.source.collectAsStateWithLifecycle()
    val tgt by vm.target.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    val engine by vm.engineName.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val turns by vm.turns.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var reverse by remember { mutableStateOf(false) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.listen(reverse) }
    LaunchedEffect(src, tgt) { vm.refresh() }
    DisposableEffect(Unit) { onDispose { vm.stop() } }
    val names = SUPPORTED_LANGUAGES.toMap()

    ScreenColumn {
        SectionHeader("Languages")
        AuralynxCard {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { Dropdown("You speak", SUPPORTED_LANGUAGES.map { it.first }, src, { names[it] ?: it }) { vm.source.value = it } }
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { Dropdown("They speak", SUPPORTED_LANGUAGES.map { it.first }, tgt, { names[it] ?: it }) { vm.target.value = it } }
            }
            TextButton(onClick = vm::swap) { Text("Swap") }
            when (availability) {
                PairAvailability.READY -> StatusPill("Ready · ${engine ?: ""}", StatusKind.LIVE)
                PairAvailability.NEEDS_DOWNLOAD -> {
                    StatusPill("Language pack needed", StatusKind.LIMITED)
                    TextButton(onClick = { runCatching { vm.downloadIntent?.send() } }) { Text("Download in system settings") }
                }
                PairAvailability.DOWNLOADING -> StatusPill("Downloading language pack", StatusKind.LIMITED)
                null -> StatusPill("Checking…", StatusKind.LIMITED)
                PairAvailability.ENGINE_UNAVAILABLE -> {
                    StatusPill("No translation service", StatusKind.UNAVAILABLE)
                    InfoNote("This device does not provide Android's system translation service, so on-device translation is unavailable for every language. You can turn on free cloud translation below.")
                }
                else -> {
                    StatusPill("Pair not supported", StatusKind.UNAVAILABLE)
                    InfoNote("The system translation service does not support this language pair. Try another pair or configure your own LibreTranslate server below.")
                }
            }
            val s = vm.support
            InfoNote(buildString {
                append(if (!s.available) "No speech recognition service is installed." else if (s.onDeviceAvailable) "Speech recognition runs on this device." else "Speech recognition is provided by your system speech service, which may use the network.")
            })
        }
        SectionHeader("Conversation")
        AuralynxCard {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                Button(enabled = !listening && vm.support.available, onClick = { reverse = false; mic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("I'm speaking") }
                OutlinedButton(enabled = !listening && vm.support.available, onClick = { reverse = true; mic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("They're speaking") }
            }
            if (listening) Text("Listening… $partial", style = MaterialTheme.typography.bodyMedium)
            turns.takeLast(8).forEach { t ->
                Text(t.heard, style = MaterialTheme.typography.bodyMedium)
                t.translated?.let { Text("→ $it", style = MaterialTheme.typography.titleMedium) }
                t.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            var typed by remember { mutableStateOf("") }
            OutlinedTextField(typed, { typed = it }, label = { Text("Or type text") }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = typed.isNotBlank(), onClick = { vm.translateTyped(typed); typed = "" }) { Text("Translate") }
            InfoNote("Translations play as media, so they are heard through your connected headphones.")
        }
        SectionHeader("Privacy")
        AuralynxCard {
            val st = settings
            SettingSwitch("Save translation history", st?.translationSaveHistory == true, vm::setSaveHistory, supporting = "Stored only on this device")
            var url by remember(st?.translationCloudUrl) { mutableStateOf(st?.translationCloudUrl.orEmpty()) }
            var key by remember { mutableStateOf("") }
            SettingSwitch("Free cloud translation (MyMemory)", st?.translationFreeCloudEnabled == true, vm::setFreeCloud,
                supporting = "Off by default. Sends the recognised text (never audio) over HTTPS to MyMemory by Translated srl. Free, no account. Limit: 5,000 characters per day, or 50,000 if you add an email. MyMemory's terms and privacy policy apply.")
            if (st?.translationFreeCloudEnabled == true) {
                val savedEmail by vm.contactEmail.collectAsStateWithLifecycle()
                var email by remember(savedEmail) { mutableStateOf(savedEmail) }
                OutlinedTextField(email, { email = it }, label = { Text("Email for the higher free limit (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), isError = email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches())
                TextButton(onClick = { vm.saveContactEmail(email) }) { Text("Save email") }
                InfoNote("Stored encrypted on this device and sent only to MyMemory as their contact address.")
            }
            Text("Your own server (optional)", style = MaterialTheme.typography.titleSmall)
            InfoNote("Off by default. If enabled, the text you speak is sent over HTTPS to the LibreTranslate server you enter. Auralynx ships with no server and no key.")
            OutlinedTextField(url, { url = it }, label = { Text("Server URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = url.isNotBlank() && !LibreTranslateEngine.isAllowedUrl(url))
            OutlinedTextField(key, { key = it }, label = { Text("API key (optional, stored encrypted)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.sm)) {
                Button(enabled = LibreTranslateEngine.isAllowedUrl(url), onClick = { vm.configureCloud(true, url, key) }) { Text("Enable") }
                OutlinedButton(onClick = { vm.configureCloud(false, url, "") }) { Text("Disable") }
            }
        }
        if (history.isNotEmpty()) {
            SectionHeader("History")
            AuralynxCard { history.take(10).forEach { h -> Text("${formatTime(h.timestampMillis)} · ${h.sourceLanguage}→${h.targetLanguage}: ${h.translatedText}", style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
