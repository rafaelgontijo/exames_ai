package br.com.exames.featureresults

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.ExamesTheme
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Proves each reviewer tool stays reachable from the adapted Resultados screen and never deletes originals. */
class ReviewerAccessUiTest {
    @get:Rule val compose = createComposeRule()
    private val runId = "a".repeat(64)
    private val derivedId = "b".repeat(64)
    private val truthSha = "c".repeat(64)
    private val region = SourceRegion(1, 10.0, 10.0, 60.0, 30.0, 100.0, 140.0)
    private val observation = Observation("obs1", "doc1", "Glicose", "90", "mg/dL", 1, "s", referenceRaw = "70-99",
        fieldEvidence = listOf(ExtractedFieldEvidence("resultRaw", "90", region, "span", "SYNTHETIC", "1")))
    private val assessment = Assessment("obs1", AssessmentStatus.NEEDS_REVIEW, ReasonCode.AMBIGUOUS_INPUT, Instant.EPOCH, "REF_NUMERIC/2.0.0")
    private val corpusCase = CorpusCase("case1", "fam1", CorpusOrigin.SYNTHETIC, CorpusFormat.PDF_TEXT, CorpusPartition.DEVELOPMENT, "l", "1",
        "d.pdf", "d".repeat(64), 10, "gt.json", "e".repeat(64))
    private val truth = GroundTruthSnapshot(truthSha, GroundTruthVersion(caseId = "case1", version = "1", reviewState = ReviewState.REVIEWED,
        reviewerId = "rev", reviewedAt = "2026-01-01T00:00:00Z", observations = emptyList()))
    private fun run(id: String = runId, parent: String? = null, state: RunState = RunState.COMPLETE) = ProcessingRun(id, "doc1", "h".repeat(64), "case1",
        ExtractionMode.BASELINE, "e", "p", "REF_NUMERIC/2.0.0", state, "2026-01-01T00:00:00Z", "d", "14", parentRunId = parent)
    private val doc = Document("doc1", "artifact:original", "h".repeat(64), "application/pdf", Instant.EPOCH, 2, corpusCase = corpusCase)

    private fun setUp(runs: List<ProcessingRun> = listOf(run())): Triple<FakeValidationRepository, RecordingReviewActions, ResultsViewModel> {
        val repo = FakeValidationRepository(doc, runs, runs.associate { it.runId to listOf(observation) }, runs.associate { it.runId to listOf(assessment) }, listOf(truth))
        val actions = RecordingReviewActions(repo, derivedId)
        val model = ResultsViewModel(repo, { _, _ -> syntheticBitmap() }, actions)
        compose.setContent { ExamesTheme { ResultsScreen(runs.last().runId, onBack = {}, model = model) } }
        compose.waitUntil(10000) { model.state.value.run != null && !model.state.value.loading }
        return Triple(repo, actions, model)
    }
    // The list is lazy: scroll the target into composition first. Dialog controls are not in the list.
    private fun click(text: String) { compose.onNodeWithTag("results-list").performScrollToNode(hasText(text)); compose.onNodeWithText(text).assertHeightIsAtLeast(48.dp).performClick() }
    private fun clickTag(tag: String) { compose.onNodeWithTag("results-list").performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).performClick() }
    private fun clickDialog(tag: String) = compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).performClick()
    private fun reopened(model: ResultsViewModel) = compose.waitUntil(10000) { model.state.value.run != null && !model.state.value.loading && !model.state.value.reviewBusy }

    @Test fun sourcePageAndHighlightedEvidenceAreReachable() {
        setUp()
        click("Ver página 1 da fonte privada")
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("private-preview")).fetchSemanticsNodes().isNotEmpty() }
        click("Fechar página")
        clickTag("evidence-original-obs1-resultRaw-1")
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("evidence-overlay")).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun correctionIsSavedAsNewRecordAndTheOriginalValueStaysVisible() {
        val (repo, actions, _) = setUp()
        clickTag("correct-obs1-RESULT")
        compose.onNodeWithTag("review-draft").performTextReplacement("25")
        clickDialog("review-save")
        compose.waitUntil(10000) { actions.calls.any { it.startsWith("correct:RESULT") } }
        assertEquals("correct:RESULT:90->25", actions.calls.single { it.startsWith("correct") })
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("review-draft")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Resultado: 90").assertExists()
        assertEquals(0, repo.deleteCalls)
    }
    @Test fun groundTruthRevisionAndComparisonAreReachable() {
        val (_, actions, _) = setUp()
        clickTag("revise-truth-$truthSha")
        compose.onNodeWithText("Revisar gabarito em nova versão").assertExists()
        compose.onNodeWithText("Descartar rascunho").assertHeightIsAtLeast(48.dp).performClick()
        clickTag("compare-truth-$truthSha")
        compose.waitUntil(10000) { actions.calls.any { it.startsWith("compare") } }
        assertEquals("compare:$truthSha", actions.calls.single { it.startsWith("compare") })
    }
    @Test fun derivedVersionLinksBackToItsOriginRun() {
        val (_, _, model) = setUp(listOf(run(), run(derivedId, parent = runId)))
        click("Abrir execução de origem")
        compose.waitUntil(10000) { model.state.value.run?.runId == runId }
        assertEquals(runId, model.state.value.run?.runId)
    }
    @Test fun reportsAreRequestedForTheCorpusRunAndPairedReportNeedsExplicitSelection() {
        val (_, actions, model) = setUp()
        compose.onNodeWithTag("results-list").performScrollToNode(hasTestTag("paired-report-$truthSha"))
        compose.onNodeWithTag("paired-report-$truthSha").assertIsNotEnabled()
        clickTag("report-$truthSha")
        compose.waitUntil(10000) { actions.calls.any { it.startsWith("report") } }
        assertEquals("report:$truthSha", actions.calls.single { it.startsWith("report") })
        compose.waitUntil(10000) { model.state.value.message?.startsWith("Relatório indisponível") == true }
    }
    @Test fun reevaluateAndReextractCreateNewRunsWithoutDeletingTheOriginal() {
        val (repo, actions, model) = setUp()
        clickTag("reevaluate")
        compose.waitUntil(10000) { actions.calls.any { it.startsWith("reevaluate") } }
        reopened(model)
        clickTag("reextract")
        compose.waitUntil(10000) { actions.calls.any { it.startsWith("reextract") } }
        assertEquals(listOf("reevaluate:$runId", "reextract:$derivedId"), actions.calls.filter { it.startsWith("re") })
        assertEquals(runId, repo.runsFlow.value.first().runId)
        assertEquals(3, repo.runsFlow.value.size)
        assertEquals(0, repo.deleteCalls)
        assertEquals(listOf(observation), repo.observationsByRun.getValue(runId))
    }
    @Test fun activeRunsHideReevaluationAndReextractionButKeepSourceAccess() {
        setUp(listOf(run(state = RunState.RUNNING)))
        compose.onAllNodes(hasTestTag("reevaluate")).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("reextract")).assertCountEquals(0)
        compose.onNodeWithTag("results-list").performScrollToNode(hasText("Ver página 1 da fonte privada"))
        compose.onNodeWithText("Ver página 1 da fonte privada").assertExists()
    }
}
