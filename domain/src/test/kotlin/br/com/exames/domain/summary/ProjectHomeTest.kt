package br.com.exames.domain.summary

import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ProjectHomeTest {
    private fun doc(id: String, at: String = "2026-01-01T00:00:00Z", mime: String = "application/pdf", st: ProcessingState = ProcessingState.IMPORTED) =
        Document(id, "uri-privada", "h", mime, Instant.parse(at), 1, st)
    private fun run(id: String, doc: String, mode: ExtractionMode = ExtractionMode.BASELINE, state: RunState = RunState.COMPLETE,
        at: String = "2026-01-01T00:00:00Z", basis: OutputBasis = OutputBasis.AUTOMATIC, outcome: InferenceOutcome = InferenceOutcome.NOT_ATTEMPTED) =
        ProcessingRun(id, doc, "h", null, mode, "e", "p", "r", state, at, "d", "14", inferenceOutcome = outcome, outputBasis = basis)
    private fun loaded(s: HomeState) = (s as HomeState.Loaded).cards

    @Test fun nullDocumentsIsUnavailableNotEmpty() {
        assertEquals(HomeState.Unavailable, ProjectHome.project(null, emptyMap()))
        assertEquals(HomeState.Empty, ProjectHome.project(emptyList(), emptyMap()))
    }
    @Test fun onlyDeletingDocumentsLeaveEmptyState() {
        assertEquals(HomeState.Empty, ProjectHome.project(listOf(doc("a", st = ProcessingState.DELETING)), emptyMap()))
    }
    @Test fun documentWithoutRunsHasNoExtractionAndIsNotCompleted() {
        val c = loaded(ProjectHome.project(listOf(doc("a")), emptyMap())).single()
        assertTrue(c.extractions.isEmpty()); assertEquals(0, c.derivedRunCount)
    }
    @Test fun unreadableRunsAreTreatedAsNoExtractionNeverAsSuccess() {
        val c = loaded(ProjectHome.project(listOf(doc("a")), mapOf("a" to null))).single()
        assertTrue(c.extractions.isEmpty())
    }
    @Test fun newestAutomaticRunWinsEvenWhenQueuedOverOlderComplete() {
        val runs = listOf(run("r1", "a", state = RunState.COMPLETE, at = "2026-01-01T00:00:00Z"),
            run("r2", "a", state = RunState.QUEUED, at = "2026-01-02T00:00:00Z"))
        val e = loaded(ProjectHome.project(listOf(doc("a")), mapOf("a" to runs))).single().extractions.single()
        assertEquals("r2", e.runId); assertEquals(RunState.QUEUED, e.state)
    }
    @Test fun failedPartialAndCancelledStatesAreReportedAsIs() {
        for (s in listOf(RunState.FAILED, RunState.PARTIAL, RunState.CANCELLED, RunState.RUNNING)) {
            val e = loaded(ProjectHome.project(listOf(doc("a")), mapOf("a" to listOf(run("r", "a", state = s))))).single().extractions.single()
            assertEquals(s, e.state)
        }
    }
    @Test fun modesAreIdentifiedSeparatelyInStableOrder() {
        val runs = listOf(run("ai", "a", ExtractionMode.ASSISTED, outcome = InferenceOutcome.FALLBACK), run("base", "a", ExtractionMode.BASELINE))
        val ex = loaded(ProjectHome.project(listOf(doc("a")), mapOf("a" to runs))).single().extractions
        assertEquals(listOf(ExtractionMode.BASELINE, ExtractionMode.ASSISTED), ex.map { it.mode })
        assertEquals(InferenceOutcome.FALLBACK, ex[1].inferenceOutcome)
    }
    @Test fun correctedAndReassessedRunsNeverReplaceAutomaticSummary() {
        val runs = listOf(run("auto", "a", at = "2026-01-01T00:00:00Z", state = RunState.PARTIAL),
            run("fix", "a", at = "2026-02-01T00:00:00Z", basis = OutputBasis.CORRECTED),
            run("re", "a", at = "2026-03-01T00:00:00Z", basis = OutputBasis.REASSESSED))
        val c = loaded(ProjectHome.project(listOf(doc("a")), mapOf("a" to runs))).single()
        assertEquals("auto", c.extractions.single().runId); assertEquals(RunState.PARTIAL, c.extractions.single().state)
        assertEquals(2, c.derivedRunCount)
    }
    @Test fun onlyDerivedRunsLeaveNoAutomaticExtraction() {
        val c = loaded(ProjectHome.project(listOf(doc("a")), mapOf("a" to listOf(run("fix", "a", basis = OutputBasis.CORRECTED))))).single()
        assertTrue(c.extractions.isEmpty()); assertEquals(1, c.derivedRunCount)
    }
    @Test fun importDatesOrderNewestFirstWithStableTieBreakAndFormatsAreGeneric() {
        val docs = listOf(doc("b", "2026-01-01T00:00:00Z", "image/png"), doc("c", "2026-03-01T00:00:00Z", "image/jpeg"),
            doc("a", "2026-01-01T00:00:00Z", "text/x-unknown"))
        val cards = loaded(ProjectHome.project(docs, emptyMap()))
        assertEquals(listOf("c", "a", "b"), cards.map { it.documentId })
        assertEquals(listOf(DocumentFormat.JPEG, DocumentFormat.OTHER, DocumentFormat.PNG), cards.map { it.format })
        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), cards.first().importedAt)
    }
    @Test fun runsOfAnotherDocumentAreIgnoredAndUnparsableDatesLose() {
        val runs = listOf(run("x", "other"), run("bad", "a", at = "ontem"), run("ok", "a", at = "2026-01-01T00:00:00Z"))
        val e = loaded(ProjectHome.project(listOf(doc("a")), mapOf("a" to runs))).single().extractions.single()
        assertEquals("ok", e.runId)
    }
}
