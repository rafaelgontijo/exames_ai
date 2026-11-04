package br.com.exames.uichecks

import android.graphics.Bitmap
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import br.com.exames.featureresults.ResultsViewModel
import br.com.exames.featureresults.ReviewActions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** Synthetic, read-only repository used only to render the Resultado screen for layout/accessibility checks. */
class SyntheticRepository(private val document: Document, private val run: ProcessingRun, private val obs: List<Observation>, private val asm: List<Assessment>) : ValidationRepository {
    override fun observeDocuments(): Flow<List<Document>> = MutableStateFlow(listOf(document))
    override suspend fun find(id: String) = document.takeIf { it.id == id }
    override suspend fun save(document: Document) = Unit
    override suspend fun deleteCompletely(id: String) = Unit
    override suspend fun importCorpusDocument(document: Document, truth: GroundTruthSnapshot) = Unit
    override fun observeRuns(documentId: String): Flow<List<ProcessingRun>> = MutableStateFlow(listOf(run))
    override suspend fun createRun(run: ProcessingRun) = Unit
    override suspend fun findRun(runId: String) = run.takeIf { it.runId == runId }
    override suspend fun recoverableRuns() = emptyList<ProcessingRun>()
    override suspend fun stopRunIfActive(runId: String, state: RunState, failure: RunFailure?): ProcessingRun? = null
    override suspend fun updateRunState(runId: String, state: RunState, outcome: InferenceOutcome) = Unit
    override suspend fun startInferenceAttempt(runId: String, page: Int, attemptId: String) = Unit
    override suspend fun cancelInferenceAttempt(runId: String, page: Int, attemptId: String) = Unit
    override suspend fun commitPage(batch: PageBatch) = true
    override suspend fun commitReevaluation(run: ProcessingRun, pages: List<PageBatch>) = true
    override suspend fun observations(runId: String) = obs
    override suspend fun reference(observationId: String): ReferenceRange? = null
    override suspend fun assessments(runId: String) = asm
    private fun pages(runId: String) = listOf(
        PageCheckpoint(runId, 1, ExtractionMode.BASELINE, PageState.COMPLETE, 1L),
        PageCheckpoint(runId, 2, ExtractionMode.BASELINE, PageState.FAILED, 1L, errorCode = "PAGE_UNSUPPORTED_LAYOUT"))
    override suspend fun checkpoints(runId: String) = pages(runId)
    override fun observeCheckpoints(runId: String): Flow<List<PageCheckpoint>> = MutableStateFlow(pages(runId))
    override suspend fun appendCorrection(correction: ManualCorrection) = Unit
    override suspend fun corrections(observationId: String) = emptyList<ManualCorrection>()
    override suspend fun appendGroundTruth(documentId: String, snapshot: GroundTruthSnapshot) = Unit
    override suspend fun groundTruth(documentId: String) = emptyList<GroundTruthSnapshot>()
    override suspend fun appendComparison(comparison: Comparison) = Unit
    override suspend fun comparisons(runId: String) = emptyList<Comparison>()
}

object SyntheticResults {
    const val RUN_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    fun model(): ResultsViewModel {
        val doc = Document("doc-sintetico", "artifact:original", "h".repeat(64), "application/pdf", Instant.EPOCH, 2)
        val run = ProcessingRun(RUN_ID, doc.id, doc.hash, null, ExtractionMode.BASELINE, "e", "p", "REF_NUMERIC/2.0.0", RunState.PARTIAL,
            "2026-01-01T00:00:00Z", "d", "14")
        val obs = listOf(
            Observation("o1", doc.id, "Glicose", "90", "mg/dL", 1, "s", referenceRaw = "70-99"),
            Observation("o2", doc.id, "Ureia", "30", "mmol/L", 1, "s", referenceRaw = "15-45"),
            Observation("o3", doc.id, "Creatinina", "9".repeat(60), "mg/dL", 1, "s"))
        val asm = listOf(
            Assessment("o1", AssessmentStatus.WITHIN_RANGE, ReasonCode.INSIDE_REFERENCE, Instant.EPOCH, "REF_NUMERIC/2.0.0"),
            Assessment("o2", AssessmentStatus.NEEDS_REVIEW, ReasonCode.UNIT_UNRESOLVED, Instant.EPOCH, "REF_NUMERIC/2.0.0"),
            Assessment("o3", AssessmentStatus.NOT_CLASSIFIABLE, ReasonCode.NO_REFERENCE, Instant.EPOCH, "REF_NUMERIC/2.0.0"))
        val actions = object : ReviewActions {
            override suspend fun correct(runId: String, observationId: String, field: CorrectionField, old: String?, value: String?, id: String) = Unit
            override suspend fun reevaluate(parentId: String, id: String) = run
            override suspend fun revise(documentId: String, parentHash: String, draft: String) = Unit
            override suspend fun reextract(documentId: String, parentId: String, id: String) = run
            override suspend fun compare(runId: String, truthHash: String) = Unit
            override suspend fun report(runId: String, truthHash: String): LocalValidationReport = error("unused")
            override suspend fun pair(baselineId: String, assistedId: String, truthHash: String): PairedValidationReport = error("unused")
        }
        return ResultsViewModel(SyntheticRepository(doc, run, obs, asm), { _, _ -> Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888) }, actions)
    }
}
