package com.cueflow.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createScriptPersistAndOpenTeleprompter() {
        val title = "Smoke ${System.currentTimeMillis()}"
        val body = "Hello from the automated test.\n\nThis script verifies local editing and the teleprompter."

        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("home_new_script").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("home_new_script").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("editor_title").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("editor_title").performTextInput(title)
        compose.onNodeWithTag("editor_content").performTextInput(body)
        compose.onNodeWithTag("editor_save").performClick()
        compose.onNodeWithTag("editor_back").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(title).assertIsDisplayed()

        compose.onNodeWithTag("play_$title").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("prompt_play_pause").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Hello from the automated test.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("prompt_play_pause").assertIsDisplayed()
        compose.onNodeWithTag("prompt_close").performClick()
    }
}
