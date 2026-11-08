package app.auralynx.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User preferences. Every privacy-relevant feature defaults to OFF. */
data class AppSettings(
    val onboardingComplete: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Off by default so the Auralynx palette is shown; Material You is opt-in. */
    val dynamicColor: Boolean = false,
    val accessoryChannelEnabled: Boolean = true,
    val experimentalEnabled: Boolean = false,
    val autoPauseOnEarRemoval: Boolean = true,
    val autoResumeOnEarInsert: Boolean = false,
    val batteryAlertsEnabled: Boolean = true,
    val lowBatteryThreshold: Int = 20,
    val chargingAlertsEnabled: Boolean = false,
    val disconnectAlertsEnabled: Boolean = false,
    val locationOnDisconnectEnabled: Boolean = false,
    val automationEnabled: Boolean = false,
    val manualOverrideMinutes: Int = 30,
    val activeProfiles: Set<String> = emptySet(),
    val diagnosticsLoggingEnabled: Boolean = false,
    val retentionDays: Int = 90,
    val selectedDeviceAddress: String? = null,
    val sessionEqProfileId: Long? = null,
    val translationCloudUrl: String? = null,
    val translationCloudEnabled: Boolean = false,
    val translationSaveHistory: Boolean = true,
    /** Opt-in free cloud translation through the public MyMemory API. */
    val translationFreeCloudEnabled: Boolean = false,
    val sleepTimerEndsAtMillis: Long? = null,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auralynx_settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.dataStore

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val channel = booleanPreferencesKey("accessory_channel_enabled")
        val experimental = booleanPreferencesKey("experimental_enabled")
        val autoPause = booleanPreferencesKey("auto_pause")
        val autoResume = booleanPreferencesKey("auto_resume")
        val batteryAlerts = booleanPreferencesKey("battery_alerts")
        val lowThreshold = intPreferencesKey("low_battery_threshold")
        val chargingAlerts = booleanPreferencesKey("charging_alerts")
        val disconnectAlerts = booleanPreferencesKey("disconnect_alerts")
        val locationOnDisconnect = booleanPreferencesKey("location_on_disconnect")
        val automation = booleanPreferencesKey("automation_enabled")
        val overrideMinutes = intPreferencesKey("override_minutes")
        val profiles = stringSetPreferencesKey("active_profiles")
        val diagnostics = booleanPreferencesKey("diagnostics_logging")
        val retention = intPreferencesKey("retention_days")
        val selected = stringPreferencesKey("selected_device")
        val sessionEq = longPreferencesKey("session_eq_profile")
        val cloudUrl = stringPreferencesKey("translation_cloud_url")
        val cloudEnabled = booleanPreferencesKey("translation_cloud_enabled")
        val saveHistory = booleanPreferencesKey("translation_save_history")
        val freeCloud = booleanPreferencesKey("translation_free_cloud")
        val sleepTimer = longPreferencesKey("sleep_timer_ends")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            onboardingComplete = p[K.onboarding] ?: d.onboardingComplete,
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            accessoryChannelEnabled = p[K.channel] ?: d.accessoryChannelEnabled,
            experimentalEnabled = p[K.experimental] ?: d.experimentalEnabled,
            autoPauseOnEarRemoval = p[K.autoPause] ?: d.autoPauseOnEarRemoval,
            autoResumeOnEarInsert = p[K.autoResume] ?: d.autoResumeOnEarInsert,
            batteryAlertsEnabled = p[K.batteryAlerts] ?: d.batteryAlertsEnabled,
            lowBatteryThreshold = p[K.lowThreshold] ?: d.lowBatteryThreshold,
            chargingAlertsEnabled = p[K.chargingAlerts] ?: d.chargingAlertsEnabled,
            disconnectAlertsEnabled = p[K.disconnectAlerts] ?: d.disconnectAlertsEnabled,
            locationOnDisconnectEnabled = p[K.locationOnDisconnect] ?: d.locationOnDisconnectEnabled,
            automationEnabled = p[K.automation] ?: d.automationEnabled,
            manualOverrideMinutes = p[K.overrideMinutes] ?: d.manualOverrideMinutes,
            activeProfiles = p[K.profiles] ?: d.activeProfiles,
            diagnosticsLoggingEnabled = p[K.diagnostics] ?: d.diagnosticsLoggingEnabled,
            retentionDays = p[K.retention] ?: d.retentionDays,
            selectedDeviceAddress = p[K.selected],
            sessionEqProfileId = p[K.sessionEq],
            translationCloudUrl = p[K.cloudUrl],
            translationCloudEnabled = p[K.cloudEnabled] ?: d.translationCloudEnabled,
            translationSaveHistory = p[K.saveHistory] ?: d.translationSaveHistory,
            translationFreeCloudEnabled = p[K.freeCloud] ?: d.translationFreeCloudEnabled,
            sleepTimerEndsAtMillis = p[K.sleepTimer],
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val s = transform(currentFrom(p))
            p[K.onboarding] = s.onboardingComplete
            p[K.theme] = s.themeMode.name
            p[K.dynamic] = s.dynamicColor
            p[K.channel] = s.accessoryChannelEnabled
            p[K.experimental] = s.experimentalEnabled
            p[K.autoPause] = s.autoPauseOnEarRemoval
            p[K.autoResume] = s.autoResumeOnEarInsert
            p[K.batteryAlerts] = s.batteryAlertsEnabled
            p[K.lowThreshold] = s.lowBatteryThreshold.coerceIn(5, 50)
            p[K.chargingAlerts] = s.chargingAlertsEnabled
            p[K.disconnectAlerts] = s.disconnectAlertsEnabled
            p[K.locationOnDisconnect] = s.locationOnDisconnectEnabled
            p[K.automation] = s.automationEnabled
            p[K.overrideMinutes] = s.manualOverrideMinutes.coerceIn(1, 24 * 60)
            p[K.profiles] = s.activeProfiles
            p[K.diagnostics] = s.diagnosticsLoggingEnabled
            p[K.retention] = s.retentionDays.coerceIn(1, 3650)
            s.selectedDeviceAddress?.let { p[K.selected] = it } ?: p.remove(K.selected)
            s.sessionEqProfileId?.let { p[K.sessionEq] = it } ?: p.remove(K.sessionEq)
            s.translationCloudUrl?.let { p[K.cloudUrl] = it } ?: p.remove(K.cloudUrl)
            p[K.cloudEnabled] = s.translationCloudEnabled
            p[K.saveHistory] = s.translationSaveHistory
            p[K.freeCloud] = s.translationFreeCloudEnabled
            s.sleepTimerEndsAtMillis?.let { p[K.sleepTimer] = it } ?: p.remove(K.sleepTimer)
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun currentFrom(p: Preferences): AppSettings = AppSettings(
        onboardingComplete = p[K.onboarding] ?: false,
        themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = p[K.dynamic] ?: false,
        accessoryChannelEnabled = p[K.channel] ?: true,
        experimentalEnabled = p[K.experimental] ?: false,
        autoPauseOnEarRemoval = p[K.autoPause] ?: true,
        autoResumeOnEarInsert = p[K.autoResume] ?: false,
        batteryAlertsEnabled = p[K.batteryAlerts] ?: true,
        lowBatteryThreshold = p[K.lowThreshold] ?: 20,
        chargingAlertsEnabled = p[K.chargingAlerts] ?: false,
        disconnectAlertsEnabled = p[K.disconnectAlerts] ?: false,
        locationOnDisconnectEnabled = p[K.locationOnDisconnect] ?: false,
        automationEnabled = p[K.automation] ?: false,
        manualOverrideMinutes = p[K.overrideMinutes] ?: 30,
        activeProfiles = p[K.profiles] ?: emptySet(),
        diagnosticsLoggingEnabled = p[K.diagnostics] ?: false,
        retentionDays = p[K.retention] ?: 90,
        selectedDeviceAddress = p[K.selected],
        sessionEqProfileId = p[K.sessionEq],
        translationCloudUrl = p[K.cloudUrl],
        translationCloudEnabled = p[K.cloudEnabled] ?: false,
        translationSaveHistory = p[K.saveHistory] ?: true,
        translationFreeCloudEnabled = p[K.freeCloud] ?: false,
        sleepTimerEndsAtMillis = p[K.sleepTimer],
    )
}
