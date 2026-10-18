package com.touristsafety.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.ui.components.StatusBanner
import com.touristsafety.app.ui.components.statusVisualsFor
import com.touristsafety.app.ui.theme.TouristSafetyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose smoke test for the primary safety indicator.
 *
 * It deliberately checks the *text*, not just the colour: a colour-blind user must be able to read
 * the same information, so the wording is part of the contract.
 *
 * NOTE: compile-checked only in this environment — no emulator or device is available, so this has
 * not been executed.
 */
@RunWith(AndroidJUnit4::class)
class StatusBannerInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun safeStatusIsShownWithItsGuidance() {
        composeRule.setContent {
            TouristSafetyTheme {
                StatusBanner(visuals = statusVisualsFor(SafetyStatus.SAFE))
            }
        }

        composeRule.onNodeWithText("Safe").assertIsDisplayed()
    }

    @Test
    fun restrictedStatusIsShownWithItsGuidance() {
        composeRule.setContent {
            TouristSafetyTheme {
                StatusBanner(visuals = statusVisualsFor(SafetyStatus.INSIDE_RESTRICTED))
            }
        }

        composeRule.onNodeWithText("Inside a RESTRICTED area").assertIsDisplayed()
    }

    @Test
    fun uncertainStatusExplainsWhyNoAlertWasRaised() {
        composeRule.setContent {
            TouristSafetyTheme {
                StatusBanner(visuals = statusVisualsFor(SafetyStatus.UNCERTAIN))
            }
        }

        composeRule.onNodeWithText("Location uncertain").assertIsDisplayed()
    }
}
