package br.com.exames.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startsOnHomeWithNavigationAndNoClinicalResults() {
        compose.onNodeWithText("Meu Exame").assertIsDisplayed()
        for (tab in listOf("Início", "Exames", "Ajuda")) compose.onNodeWithContentDescription(tab).assertIsDisplayed()
    }
    @Test fun homeShowsEmptyStateWithoutPrototypeExamples() {
        compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Meus exames")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Meus exames").assertIsDisplayed()
        compose.waitUntil(10000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("home-empty")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(androidx.compose.ui.test.hasText("Importado em", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        for (fixed in listOf("Exame de urina", "Exame de sangue", "2 alterados", "Tudo normal", "LDL", "Hemoglobina", "12 de outubro", "3 de agosto"))
            compose.onAllNodes(androidx.compose.ui.test.hasText(fixed, substring = true)).assertCountEquals(0)
    }
    @Test fun sendScreenStatesLocalProcessingAndKeepsModesAndCorpusAccess() {
        compose.onNodeWithText("Enviar exame").performClick()
        compose.onNodeWithText("O processamento acontece neste aparelho", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Modo sem IA").assertIsDisplayed()
        compose.onNodeWithText("Modo assistido local").assertIsDisplayed()
        compose.onNodeWithText("Importar pacote do corpus").assertExists()
    }
    @Test fun sendFlowHidesTabsAndBackReturnsHomeWithoutRecreatingImport() {
        compose.onNodeWithText("Enviar exame").performClick()
        compose.onNodeWithText("Selecionar documento").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajuda").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithText("Meu Exame").assertIsDisplayed()
    }
    @Test fun switchingTabsAndBackKeepsTheShellConsistent() {
        compose.onNodeWithContentDescription("Exames").performClick()
        compose.onNodeWithText("Histórico local").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajuda").performClick()
        compose.onNodeWithText("Como revisar uma leitura e o que acontece com seus dados.").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("Meu Exame").assertIsDisplayed()
    }
    @Test fun helpExplainsReviewAndReachesTheWorkbenchList() {
        compose.onNodeWithContentDescription("Ajuda").performClick()
        compose.onNodeWithText("Como ler o resultado").assertIsDisplayed()
        compose.onNodeWithText("Abrir lista de exames").performScrollTo().performClick()
        compose.onNodeWithText("Histórico local").assertIsDisplayed()
    }
    @Test fun recreationRestoresOnlyTheDestination() {
        compose.onNodeWithContentDescription("Ajuda").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Como revisar uma leitura e o que acontece com seus dados.").assertIsDisplayed()
    }
}
