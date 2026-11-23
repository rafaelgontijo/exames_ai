package br.com.exames.app

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class PickerCancellationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun backFromAndroidPickerKeepsHome() {
        compose.onNodeWithText("Selecionar PDF").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(150, 5000)
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 4")).use { command ->
            check(command.read() == -1)
        }
        automation.waitForIdle(150, 5000)
        compose.waitUntil(10_000) {
            runCatching { compose.onAllNodesWithText("Selecionar PDF").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        compose.onNodeWithText("Selecionar PDF").assertIsDisplayed()
        compose.onNodeWithText("Olá! Vamos conferir o seu exame?").assertIsDisplayed()
    }
}
