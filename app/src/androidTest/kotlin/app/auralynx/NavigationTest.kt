package app.auralynx

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * UI navigation on a real device/emulator with the real dependency graph. No accessory is
 * connected in CI, so the dashboard must show the disconnected/off state, never fabricated data.
 */
@HiltAndroidTest
class NavigationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { hilt.inject() }

    private fun passOnboardingIfShown() {
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Skip").performClick()
            compose.onNodeWithText("Continue").performClick()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Device").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun dashboard_showsHonestEmptyState() {
        passOnboardingIfShown()
        val noAccessory = listOf("No accessory connected", "Bluetooth is off", "Bluetooth not available")
        compose.waitUntil(10_000) { noAccessory.any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() } }
        // No battery percentage may appear without an accessory.
        check(compose.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isEmpty()) { "Battery value shown without an accessory" }
    }

    @Test fun bottomNavigation_reachesEveryTab() {
        passOnboardingIfShown()
        compose.onNodeWithText("Audio").performClick()
        compose.onNodeWithText("Equalizer").assertIsDisplayed()
        compose.onNodeWithText("Automation").performClick()
        compose.onNodeWithText("Smart automation").assertIsDisplayed()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Security & privacy").assertIsDisplayed()
    }

    @Test fun audioHub_opensEqualizerWithScopeDisclosure() {
        passOnboardingIfShown()
        compose.onNodeWithText("Audio").performClick()
        compose.onNodeWithText("Equalizer").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Flat").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Where it applies", ignoreCase = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This is not a system-wide equalizer", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun experimentalScreen_isLabelledAndExplainsUnavailableFeatures() {
        passOnboardingIfShown()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Experimental").performClick()
        compose.onNodeWithText("Research features", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Not available", ignoreCase = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsAndAbout_open() {
        passOnboardingIfShown()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Accessory channel").assertIsDisplayed()
    }
}
