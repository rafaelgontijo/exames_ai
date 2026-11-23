package br.com.exames.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.EncryptedExamHistoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PickerSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun providerSelectionReachesRealImportAndDelete() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assumeTrue(target.packageName == "br.com.exames.offline.validation.extraction")
        val testPipe = InstrumentationRegistry.getArguments().getString("pickerTransportPipe") == "true"
        val label = if (testPipe) AuthorizedExamDocumentsProvider.AUTHORIZED_LABEL
        else AuthorizedExamDocumentsProvider.CONTROL_LABEL
        val store = EncryptedExamHistoryStore(target)
        check(store.list().isEmpty())
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                compose.onNodeWithText("Selecionar PDF").performClick()
                ExamPickerAutomation(instrumentation.uiAutomation).choose(label)
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithText("Não foi possível abrir o PDF", substring = true).fetchSemanticsNode() }.isSuccess
                }
                check(store.list().size == 1 && store.list().single().interrupted)
                compose.onNodeWithContentDescription("Excluir Laudo laboratorial · PDF").performScrollTo().performClick()
                compose.onNodeWithText("Descartar", substring = false).performClick()
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithText("Extração descartada.").fetchSemanticsNode() }.isSuccess
                }
                check(store.list().isEmpty())
            } finally {
                store.deleteAll()
            }
        }
    }
}
