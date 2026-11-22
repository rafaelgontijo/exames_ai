@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package br.com.exames.featurehistory

import androidx.lifecycle.ViewModelStore
import br.com.exames.domain.model.Document
import br.com.exames.domain.summary.HomeState
import br.com.exames.domain.validation.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class HomeViewModelTest {
    private val repository = mockk<ValidationRepository>()
    private val store = ViewModelStore()
    private val doc = Document("doc", "artifact:original", "a".repeat(64), "application/pdf", Instant.EPOCH, 1)
    private fun run(id: String, state: RunState, at: String, basis: OutputBasis = OutputBasis.AUTOMATIC) =
        ProcessingRun(id, "doc", "a".repeat(64), null, ExtractionMode.BASELINE, "e", "p", "r", state, at, "d", "14", outputBasis = basis)
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun model() = HomeViewModel(repository).also { store.put("m", it) }

    @Test fun emptyStoreIsEmptyNotUnavailableAndNothingIsPreloaded() = runTest {
        every { repository.observeDocuments() } returns flowOf(emptyList())
        val m = model(); assertTrue(m.state.value.loading); advanceUntilIdle()
        assertEquals(HomeState.Empty, m.state.value.home); assertFalse(m.state.value.loading)
    }
    @Test fun storageFailureIsUnavailableAndRetryRecovers() = runTest {
        every { repository.observeDocuments() } returns flow { error("PRIVATE_KEY_FAILURE_BODY") }
        val m = model(); advanceUntilIdle()
        assertEquals(HomeState.Unavailable, m.state.value.home)
        assertFalse(m.state.value.toString().contains("PRIVATE_KEY_FAILURE_BODY"))
        every { repository.observeDocuments() } returns flowOf(emptyList()); m.reload(); advanceUntilIdle()
        assertEquals(HomeState.Empty, m.state.value.home)
    }
    @Test fun latestAutomaticRunStateIsShownAndCorrectedRunsStaySeparate() = runTest {
        every { repository.observeDocuments() } returns flowOf(listOf(doc))
        every { repository.observeRuns("doc") } returns flowOf(listOf(
            run("old", RunState.COMPLETE, "2026-01-01T00:00:00Z"), run("new", RunState.PARTIAL, "2026-01-02T00:00:00Z"),
            run("fix", RunState.COMPLETE, "2026-01-03T00:00:00Z", OutputBasis.CORRECTED)))
        val m = model(); advanceUntilIdle()
        val card = (m.state.value.home as HomeState.Loaded).cards.single()
        assertEquals("new", card.extractions.single().runId); assertEquals(RunState.PARTIAL, card.extractions.single().state)
        assertEquals(1, card.derivedRunCount)
    }
    @Test fun unreadableRunsOfOneDocumentDoNotHideTheDocumentOrClaimSuccess() = runTest {
        every { repository.observeDocuments() } returns flowOf(listOf(doc))
        every { repository.observeRuns("doc") } returns flow { error("RUNS_BROKEN") }
        val m = model(); advanceUntilIdle()
        val card = (m.state.value.home as HomeState.Loaded).cards.single()
        assertTrue(card.extractions.isEmpty())
    }
    @Test fun runUpdatesFlowIntoTheState() = runTest {
        val runs = MutableStateFlow(listOf(run("r", RunState.QUEUED, "2026-01-01T00:00:00Z")))
        every { repository.observeDocuments() } returns flowOf(listOf(doc)); every { repository.observeRuns("doc") } returns runs
        val m = model(); advanceUntilIdle()
        runs.value = listOf(run("r", RunState.COMPLETE, "2026-01-01T00:00:00Z")); advanceUntilIdle()
        assertEquals(RunState.COMPLETE, (m.state.value.home as HomeState.Loaded).cards.single().extractions.single().state)
    }
}
