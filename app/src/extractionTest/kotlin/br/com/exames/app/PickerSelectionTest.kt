package br.com.exames.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.CurrentExamStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PickerSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun providerSelectionReachesRealImportAndDiscard() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assumeTrue(target.packageName == "br.com.exames.offline.validation.extraction")
        val testPipe = InstrumentationRegistry.getArguments().getString("pickerTransportPipe") == "true"
        val label = if (testPipe) AuthorizedExamDocumentsProvider.AUTHORIZED_LABEL
            else AuthorizedExamDocumentsProvider.CONTROL_LABEL
        val store = CurrentExamStore(target)
        check(store.current() == null)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                compose.onNodeWithText("Selecionar PDF").performClick()
                ExamPickerAutomation(instrumentation.uiAutomation).choose(label)
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithText("Não foi possível abrir o PDF", substring = true).fetchSemanticsNode() }.isSuccess
                }
                check(store.current()?.extraction == null && store.current() != null)
                compose.onNodeWithText("Descartar extração").performClick()
                compose.onNodeWithText("Descartar", substring = false).performClick()
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithText("Extração descartada.").fetchSemanticsNode() }.isSuccess
                }
                check(store.current() == null)
            } finally {
                store.discard()
            }
        }
    }
}
