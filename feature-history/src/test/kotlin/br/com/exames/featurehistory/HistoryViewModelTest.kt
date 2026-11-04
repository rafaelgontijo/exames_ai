@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package br.com.exames.featurehistory

import androidx.lifecycle.ViewModelStore
import br.com.exames.domain.model.Document
import br.com.exames.domain.usecase.StartProcessingRun
import br.com.exames.domain.validation.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class HistoryViewModelTest {
    private val repository = mockk<ValidationRepository>()
    private val starter = mockk<StartProcessingRun>()
    private val store = ViewModelStore()
    private val document = Document("doc", "artifact:original", "a".repeat(64), "application/pdf", Instant.EPOCH, 1)
    private val documents = MutableStateFlow(listOf(document, document.copy(id = "other")))
    private val runs = MutableStateFlow<List<ProcessingRun>>(emptyList())
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { repository.observeDocuments() } returns documents
        every { repository.observeRuns(any()) } returns runs
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private val preparation=mockk<br.com.exames.domain.inference.PrepareLocalInference>()
    private fun model() = HistoryViewModel(repository, starter, preparation).also { store.put("model", it) }

    @Test fun deletionHidesSelectedDocumentAndExecutionsWhilePreservingUnrelatedDocuments() = runTest {
        coEvery { repository.deleteCompletely("doc") } answers { documents.value = documents.value.filterNot { it.id == "doc" } }
        val model = model(); advanceUntilIdle(); model.selectDocument("doc"); advanceUntilIdle()
        model.deleteDocument("doc"); advanceUntilIdle()
        assertEquals(listOf("other"), model.state.value.documents.map { it.id })
        assertNull(model.state.value.selectedDocumentId); assertTrue(model.state.value.runs.isEmpty())
        coVerify(exactly = 1) { repository.deleteCompletely("doc") }
    }

    @Test fun protectedStorageFailureIsUnavailableRatherThanAnEmptySuccessfulHistoryAndCanRetry() = runTest {
        every { repository.observeDocuments() } returns flow { error("PRIVATE_KEY_FAILURE_BODY") }
        val model = model(); advanceUntilIdle()
        assertTrue(model.state.value.unavailable); assertFalse(model.state.value.loading)
        assertFalse(model.state.value.toString().contains("PRIVATE_KEY_FAILURE_BODY"))
        every { repository.observeDocuments() } returns documents
        model.reload(); advanceUntilIdle()
        assertFalse(model.state.value.unavailable); assertEquals(2, model.state.value.documents.size)
    }
}
