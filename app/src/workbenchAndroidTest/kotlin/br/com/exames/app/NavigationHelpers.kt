package br.com.exames.app

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

/** Opens the Enviar screen from Início. No-op when Enviar is already shown or the app is on another screen. */
fun ComposeTestRule.openSend() {
    if (onAllNodes(hasText("Selecionar documento")).fetchSemanticsNodes().isNotEmpty()) return
    if (onAllNodes(hasText("Enviar exame")).fetchSemanticsNodes().isNotEmpty() &&
        onAllNodes(hasText("Meus exames")).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Enviar exame").performClick()
        waitUntil(5000) { onAllNodes(hasText("Selecionar documento")).fetchSemanticsNodes().isNotEmpty() }
    }
}
