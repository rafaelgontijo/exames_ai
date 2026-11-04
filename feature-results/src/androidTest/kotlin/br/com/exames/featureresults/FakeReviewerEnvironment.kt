package br.com.exames.featureresults

import android.graphics.Bitmap
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** In-memory ValidationRepository for UI tests. Counts destructive calls so tests can prove originals survive. */
class FakeValidationRepository(
    var document: Document, runs: List<ProcessingRun>, val observationsByRun: Map<String, List<Observation>>,
    val assessmentsByRun: Map<String, List<Assessment>>, val truths: List<GroundTruthSnapshot> = emptyList(),
) : ValidationRepository {
    val runsFlow = MutableStateFlow(runs)
    var deleteCalls = 0
    val appendedCorrections = mutableListOf<ManualCorrection>()

    override fun observeDocuments(): Flow<List<Document>> = MutableStateFlow(listOf(document))
    override suspend fun find(id: String): Document? = document.takeIf { it.id == id }
    override suspend fun save(document: Document) = Unit
    override suspend fun deleteCompletely(id: String) { deleteCalls++ }
    override suspend fun importCorpusDocument(document: Document, truth: GroundTruthSnapshot) = Unit
    override fun observeRuns(documentId: String): Flow<List<ProcessingRun>> = runsFlow
    override suspend fun createRun(run: ProcessingRun) = Unit
    override suspend fun findRun(runId: String): ProcessingRun? = runsFlow.value.singleOrNull { it.runId == runId }
    override suspend fun recoverableRuns(): List<ProcessingRun> = emptyList()
    override suspend fun stopRunIfActive(runId: String, state: RunState, failure: RunFailure?): ProcessingRun? = null
    override suspend fun updateRunState(runId: String, state: RunState, outcome: InferenceOutcome) = Unit
    override suspend fun startInferenceAttempt(runId: String, page: Int, attemptId: String) = Unit
    override suspend fun cancelInferenceAttempt(runId: String, page: Int, attemptId: String) = Unit
    override suspend fun commitPage(batch: PageBatch): Boolean = true
    override suspend fun commitReevaluation(run: ProcessingRun, pages: List<PageBatch>): Boolean = true
    override suspend fun observations(runId: String): List<Observation> = observationsByRun[runId].orEmpty()
    override suspend fun reference(observationId: String): ReferenceRange? = null
    override suspend fun assessments(runId: String): List<Assessment> = assessmentsByRun[runId].orEmpty()
    override suspend fun checkpoints(runId: String): List<PageCheckpoint> = emptyList()
    override fun observeCheckpoints(runId: String): Flow<List<PageCheckpoint>> = MutableStateFlow(emptyList())
    override suspend fun appendCorrection(correction: ManualCorrection) { appendedCorrections += correction }
    override suspend fun corrections(observationId: String): List<ManualCorrection> = appendedCorrections.filter { it.observationId == observationId }
    override suspend fun appendGroundTruth(documentId: String, snapshot: GroundTruthSnapshot) = Unit
    override suspend fun groundTruth(documentId: String): List<GroundTruthSnapshot> = truths
    override suspend fun appendComparison(comparison: Comparison) = Unit
    override suspend fun comparisons(runId: String): List<Comparison> = emptyList()
}

class RecordingReviewActions(private val repository: FakeValidationRepository, private val derivedRunId: String) : ReviewActions {
    val calls = mutableListOf<String>()
    /** New runs are added next to the parent; the parent and its observations are never touched. */
    private fun derived(parentId: String, basis: OutputBasis): ProcessingRun {
        val parent = requireNotNull(repository.runsFlow.value.single { it.runId == parentId })
        val id = derivedRunId.takeIf { repository.runsFlow.value.none { r -> r.runId == it } } ?: "d".repeat(64)
        val child = parent.copy(runId = id, parentRunId = parentId, outputBasis = basis)
        repository.runsFlow.value = repository.runsFlow.value + child
        return child
    }
    override suspend fun correct(runId: String, observationId: String, field: CorrectionField, old: String?, value: String?, id: String) {
        calls += "correct:$field:$old->$value"
        repository.appendedCorrections += ManualCorrection(id, observationId, field, old, value, Instant.EPOCH)
    }
    override suspend fun reevaluate(parentId: String, id: String): ProcessingRun { calls += "reevaluate:$parentId"; return derived(parentId, OutputBasis.REASSESSED) }
    override suspend fun revise(documentId: String, parentHash: String, draft: String) { calls += "revise" }
    override suspend fun reextract(documentId: String, parentId: String, id: String): ProcessingRun { calls += "reextract:$parentId"; return derived(parentId, OutputBasis.AUTOMATIC) }
    override suspend fun compare(runId: String, truthHash: String) { calls += "compare:$truthHash" }
    override suspend fun report(runId: String, truthHash: String): LocalValidationReport { calls += "report:$truthHash"; error("REPORT_NOT_AVAILABLE_IN_UI_TEST") }
    override suspend fun pair(baselineId: String, assistedId: String, truthHash: String): PairedValidationReport { calls += "pair"; error("REPORT_NOT_AVAILABLE_IN_UI_TEST") }
}

fun syntheticBitmap(): Bitmap = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
