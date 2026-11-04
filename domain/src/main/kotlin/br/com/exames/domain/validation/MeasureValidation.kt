package br.com.exames.domain.validation

import br.com.exames.domain.model.ReferenceRange
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import java.time.Instant

data class DualValidationMeasurement(val run: ProcessingRun, val truth: GroundTruthSnapshot,
    val endToEnd: ValidationMetrics, val isolated: IsolatedEvaluatorReport, val comparisons: List<Comparison>)

/** Reads one immutable run and one explicit truth hash. Never calls extraction or writes its output. */
class MeasureValidation(private val repository: ValidationRepository, private val clock: Clock = Clock.systemUTC()) {
    suspend fun measure(runId: String, truthSha256: String): DualValidationMeasurement {
        val run = requireNotNull(repository.findRun(runId)) { "RUN_UNAVAILABLE" }
        require(run.state in setOf(RunState.COMPLETE, RunState.PARTIAL, RunState.FAILED, RunState.CANCELLED)) { "RUN_STILL_ACTIVE" }
        val truth = requireNotNull(repository.groundTruth(run.documentId).singleOrNull { it.sha256 == truthSha256 }) { "TRUTH_UNAVAILABLE" }
        require(run.caseId == null || run.caseId == truth.groundTruth.caseId) { "CASE_MISMATCH" }
        val observations = repository.observations(runId)
        require(observations.all { it.documentId == run.documentId }) { "DOCUMENT_MISMATCH" }
        val references = mutableMapOf<String, ReferenceRange>()
        for (observation in observations) repository.reference(observation.id)?.let { references[observation.id] = it }
        val assessments = repository.assessments(runId)
        require(assessments.map { it.observationId }.distinct().size == assessments.size) { "DUPLICATE_ASSESSMENT" }
        val endToEnd = ValidationMetricsCalculator().compute(truth.groundTruth, observations, references,
            assessments.associateBy { it.observationId }, run.state == RunState.COMPLETE)
        val isolated = IsolatedEvaluatorMeasurement(clock).measure(truth.groundTruth)
        val records = mutableListOf<Comparison>()
        if (truth.groundTruth.hasReviewedMetadata()) {
            val comparison = requireNotNull(endToEnd.comparison)
            val fields = comparison.raw.filterNot { it.matches }.map { FieldDifference(it.expectedId, "raw.${it.field}", it.expected, it.actual) } +
                comparison.canonical.filterNot { it.matches }.map { FieldDifference(it.expectedId, "canonical.${it.field}", it.expected, it.actual) }
            fun record(kind: MeasurementKind, version: String, differences: List<FieldDifference>, missing: List<String>, extra: List<String>,
                rule: String, evaluated: Int, unavailable: Map<String, String> = emptyMap()) = Comparison(
                StableIds.of(runId, truth.sha256, kind.name, version, rule), runId, truth.groundTruth.caseId,
                truth.groundTruth.version, truth.sha256, Instant.now(clock).toString(), differences, missing, extra,
                kind, version, rule, evaluated, unavailable)
            records += record(MeasurementKind.END_TO_END, ExtractionComparator.VERSION, fields, comparison.matching.missing,
                comparison.matching.extra, run.ruleVersion, comparison.matching.pairs.size)
            val isolatedDifferences = isolated.observations.flatMap { row -> listOfNotNull(
                if (row.statusMatches == false) FieldDifference(row.observationId, "isolated.status",
                    truth.groundTruth.observations.single { it.observationId == row.observationId }.expectedStatus.name, row.assessment?.status?.name) else null,
                if (row.reasonMatches == false) FieldDifference(row.observationId, "isolated.reason",
                    truth.groundTruth.observations.single { it.observationId == row.observationId }.expectedReason, row.assessment?.reasonCode?.name) else null) }
            records += record(MeasurementKind.EVALUATOR_ISOLATED, IsolatedEvaluatorMeasurement.VERSION, isolatedDifferences,
                emptyList(), emptyList(), isolated.ruleVersion, isolated.evaluated,
                isolated.observations.filter { it.unavailable != null }.associate { it.observationId to it.unavailable!!.name })
            val existing = repository.comparisons(runId).associateBy { it.comparisonId }
            for ((index, record) in records.withIndex()) {
                currentCoroutineContext().ensureActive()
                val before = existing[record.comparisonId]
                if (before == null) repository.appendComparison(record)
                else {
                    require(before.copy(comparedAt = record.comparedAt) == record) { "IMMUTABLE_COMPARISON_MISMATCH" }
                    records[index] = before
                }
            }
        }
        return DualValidationMeasurement(run, truth, endToEnd, isolated, records)
    }
}
