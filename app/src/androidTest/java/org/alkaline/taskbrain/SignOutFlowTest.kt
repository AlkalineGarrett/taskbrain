package org.alkaline.taskbrain

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickContentDescription
import org.alkaline.taskbrain.EmulatorTestSupport.waitAndClickText
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Taps the top-bar overflow → Sign out and verifies the Google sign-in
 * screen replaces the signed-in nav graph.
 *
 * Leaves auth signed-out at process end. Other test classes recover via
 * [EmulatorTestSupport.requireEmulatorAndSignIn], which mints a fresh
 * anonymous UID; tests that depend on prior emulator state will not see
 * data from runs preceding this one.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SignOutFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun classSetUp() = EmulatorTestSupport.requireEmulatorAndSignIn()
    }

    @Test
    fun signOutReturnsToLoginScreen() {
        val activity = composeTestRule.activity
        val settingsLabel = activity.getString(R.string.action_settings)
        val signOutLabel = activity.getString(R.string.action_sign_out)
        val signInTitle = activity.getString(R.string.google_title_text)
        val signInButton = activity.getString(R.string.sign_in_google)

        composeTestRule.waitAndClickContentDescription(settingsLabel)
        composeTestRule.waitAndClickText(signOutLabel, timeoutMillis = 5_000)

        composeTestRule.waitUntilAtLeastOneExists(hasText(signInButton), 10_000)
        composeTestRule.onNodeWithText(signInTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText(signInButton).assertIsDisplayed()
    }
}
