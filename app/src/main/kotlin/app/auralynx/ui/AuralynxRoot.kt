package app.auralynx.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.auralynx.core.data.settings.ThemeMode
import app.auralynx.core.designsystem.AuralynxTheme
import app.auralynx.core.designsystem.LocalReducedMotion
import app.auralynx.core.designsystem.Tokens
import app.auralynx.ui.audio.AudioHubScreen
import app.auralynx.ui.audio.EqualizerScreen
import app.auralynx.ui.audio.RecorderScreen
import app.auralynx.ui.audio.SpatialScreen
import app.auralynx.ui.automation.AutomationScreen
import app.auralynx.ui.automation.RuleEditorScreen
import app.auralynx.ui.battery.BatteryScreen
import app.auralynx.ui.device.AirPodsMaxScreen
import app.auralynx.ui.device.DeviceViewModel
import app.auralynx.ui.device.DiscoveryScreen
import app.auralynx.ui.device.GesturesScreen
import app.auralynx.ui.device.HomeScreen
import app.auralynx.ui.device.NoiseControlScreen
import app.auralynx.ui.experimental.ExperimentalScreen
import app.auralynx.ui.finder.FinderScreen
import app.auralynx.ui.onboarding.OnboardingScreen
import app.auralynx.ui.onboarding.PermissionsScreen
import app.auralynx.ui.security.SecurityScreen
import app.auralynx.ui.settings.AboutScreen
import app.auralynx.ui.settings.MoreScreen
import app.auralynx.ui.settings.SettingsScreen
import app.auralynx.ui.settings.SettingsViewModel
import app.auralynx.ui.translation.TranslationScreen
import kotlinx.coroutines.launch

private data class Tab(val route: Route, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab(Route.Home, "Device", Icons.Outlined.Headphones),
    Tab(Route.AudioHub, "Audio", Icons.Outlined.GraphicEq),
    Tab(Route.Automation, "Automation", Icons.Outlined.AutoMode),
    Tab(Route.More, "More", Icons.Outlined.MoreHoriz),
)

private fun titleOf(route: Route?): String? = when (route) {
    Route.Discovery -> "Devices"; Route.NoiseControl -> "Noise control"; Route.Battery -> "Battery"
    Route.Gestures -> "Press gestures"; Route.AirPodsMax -> "AirPods Max"; is Route.RuleEditor -> "Rule"
    Route.Equalizer -> "Equalizer"; Route.Spatial -> "Spatial audio"; Route.Recorder -> "Recorder"
    Route.Translation -> "Live translation"; Route.Finder -> "Find accessory"; Route.Security -> "Security & privacy"
    Route.Experimental -> "Experimental"; Route.Settings -> "Settings"; Route.About -> "About"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuralynxRoot(deepLink: Route?) {
    val settingsVm: SettingsViewModel = hiltViewModel()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return // DataStore loads in a few ms; splash screen covers it
    val dark = when (s.themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }

    AuralynxTheme(darkTheme = dark, dynamicColor = s.dynamicColor) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val dest = entry?.destination
        val deviceVm: DeviceViewModel = hiltViewModel()
        val snackbar = remember { SnackbarHostState() }
        val message by deviceVm.message.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        LaunchedEffect(message) { message?.let { scope.launch { snackbar.showSnackbar(it) }; deviceVm.consumeMessage() } }
        LaunchedEffect(deepLink) { deepLink?.let { nav.navigate(it) } }

        val isTab = tabs.any { t -> dest?.hasRoute(t.route::class) == true }
        val inOnboarding = dest?.hasRoute(Route.Onboarding::class) == true || dest?.hasRoute(Route.Permissions::class) == true
        val currentRoute: Route? = runCatching {
            when {
                dest?.hasRoute(Route.RuleEditor::class) == true -> entry?.toRoute<Route.RuleEditor>()
                else -> listOf(Route.Discovery, Route.NoiseControl, Route.Battery, Route.Gestures, Route.AirPodsMax, Route.Equalizer, Route.Spatial,
                    Route.Recorder, Route.Translation, Route.Finder, Route.Security, Route.Experimental, Route.Settings, Route.About)
                    .firstOrNull { dest?.hasRoute(it::class) == true }
            }
        }.getOrNull()
        val reduced = LocalReducedMotion.current
        val enter: EnterTransition = if (reduced) EnterTransition.None else fadeIn(tween(Tokens.motionMedium))
        val exit: ExitTransition = if (reduced) ExitTransition.None else fadeOut(tween(Tokens.motionShort))

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                titleOf(currentRoute)?.let { title ->
                    TopAppBar(title = { Text(title) }, navigationIcon = {
                        IconButton(onClick = { nav.navigateUp() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    })
                }
            },
            bottomBar = {
                if (isTab && !inOnboarding) NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = dest?.hasRoute(t.route::class) == true,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true; restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            val go: (Route) -> Unit = { nav.navigate(it) }
            NavHost(nav, startDestination = if (s.onboardingComplete) Route.Home else Route.Onboarding, Modifier.padding(padding),
                enterTransition = { enter }, exitTransition = { exit }) {
                composable<Route.Onboarding> { OnboardingScreen { nav.navigate(Route.Permissions) } }
                composable<Route.Permissions> {
                    PermissionsScreen {
                        settingsVm.update { it.copy(onboardingComplete = true) }
                        nav.navigate(Route.Home) { popUpTo(0) }
                    }
                }
                composable<Route.Home> { HomeScreen(deviceVm, go) }
                composable<Route.AudioHub> { AudioHubScreen(go) }
                composable<Route.Automation> { AutomationScreen(hiltViewModel(), go) }
                composable<Route.More> { MoreScreen(go) }
                composable<Route.Discovery> { DiscoveryScreen(deviceVm, go) }
                composable<Route.NoiseControl> { NoiseControlScreen(deviceVm, go) }
                composable<Route.Battery> { BatteryScreen(hiltViewModel()) }
                composable<Route.Gestures> { GesturesScreen(deviceVm, go) }
                composable<Route.AirPodsMax> { AirPodsMaxScreen(deviceVm, go) }
                composable<Route.RuleEditor> { e -> RuleEditorScreen(hiltViewModel(), e.toRoute<Route.RuleEditor>().ruleId) { nav.navigateUp() } }
                composable<Route.Equalizer> { EqualizerScreen(hiltViewModel()) }
                composable<Route.Spatial> { SpatialScreen(hiltViewModel()) }
                composable<Route.Recorder> { RecorderScreen(hiltViewModel()) }
                composable<Route.Translation> { TranslationScreen(hiltViewModel()) }
                composable<Route.Finder> { FinderScreen(hiltViewModel()) }
                composable<Route.Security> { SecurityScreen(hiltViewModel()) }
                composable<Route.Experimental> { ExperimentalScreen(hiltViewModel()) }
                composable<Route.Settings> { SettingsScreen(settingsVm) }
                composable<Route.About> { AboutScreen() }
            }
        }
    }
}
