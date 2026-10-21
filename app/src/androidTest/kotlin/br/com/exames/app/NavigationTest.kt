package br.com.exames.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun initialNavigationHasNoClinicalResults() {
        compose.onNodeWithText("Selecionar documento").assertIsDisplayed()
        compose.onNodeWithText("Revisar resultados").performClick()
        compose.onNodeWithText("Nenhum resultado disponível para revisão.").assertIsDisplayed()
        compose.onNodeWithText("Voltar").performClick()
        compose.onNodeWithText("Histórico local").performClick()
        compose.onNodeWithText("Nenhum laudo salvo nesta base inicial.").assertIsDisplayed()
    }
}
