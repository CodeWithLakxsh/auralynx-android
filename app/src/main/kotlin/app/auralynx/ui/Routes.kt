package app.auralynx.ui

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
sealed interface Route {
    @Serializable data object Onboarding : Route
    @Serializable data object Permissions : Route
    @Serializable data object Home : Route
    @Serializable data object AudioHub : Route
    @Serializable data object Automation : Route
    @Serializable data object More : Route
    @Serializable data object Discovery : Route
    @Serializable data object NoiseControl : Route
    @Serializable data object Battery : Route
    @Serializable data object Gestures : Route
    @Serializable data object AirPodsMax : Route
    @Serializable data class RuleEditor(val ruleId: Long = 0) : Route
    @Serializable data object Equalizer : Route
    @Serializable data object Spatial : Route
    @Serializable data object Recorder : Route
    @Serializable data object Translation : Route
    @Serializable data object Finder : Route
    @Serializable data object Security : Route
    @Serializable data object Experimental : Route
    @Serializable data object Settings : Route
    @Serializable data object About : Route
}

/** Deep links from shortcuts (auralynx://battery etc.). */
fun routeForDeepLink(host: String?): Route? = when (host) {
    "battery" -> Route.Battery
    "noise" -> Route.NoiseControl
    "automation" -> Route.Automation
    "finder" -> Route.Finder
    else -> null
}
