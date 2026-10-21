package br.com.exames.domain.validation

import br.com.exames.domain.model.*
import br.com.exames.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

data class PageBatch(
    val checkpoint: PageCheckpoint,
    val observations: List<Observation>,
    val references: List<ReferenceRange>,
    val assessments: List<Assessment>,
)

@Serializable
data class GroundTruthSnapshot(val sha256: String, val groundTruth: GroundTruthVersion) {
    init { require(sha256.matches(Regex("[0-9a-f]{64}"))) }
}

/** A page is committed atomically; all reads and comparisons address an explicit immutable run. */
interface ValidationRepository : DocumentRepository {
    fun observeRuns(documentId: String): Flow<List<ProcessingRun>>
    suspend fun createRun(run: ProcessingRun)
    suspend fun findRun(runId: String): ProcessingRun?
    suspend fun updateRunState(runId: String, state: RunState, outcome: InferenceOutcome)
    suspend fun commitPage(batch: PageBatch): Boolean
    suspend fun observations(runId: String): List<Observation>
    suspend fun reference(observationId: String): ReferenceRange?
    suspend fun assessments(runId: String): List<Assessment>
    suspend fun checkpoints(runId: String): List<PageCheckpoint>
    suspend fun appendCorrection(correction: ManualCorrection)
    suspend fun corrections(observationId: String): List<ManualCorrection>
    suspend fun appendGroundTruth(documentId: String, snapshot: GroundTruthSnapshot)
    suspend fun groundTruth(documentId: String): List<GroundTruthSnapshot>
    suspend fun appendComparison(comparison: Comparison)
    suspend fun comparisons(runId: String): List<Comparison>
}
