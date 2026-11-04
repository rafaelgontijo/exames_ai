package br.com.exames.domain.validation

import br.com.exames.domain.model.*
import br.com.exames.domain.parser.BiochemistryParser
import kotlinx.serialization.Serializable

@Serializable enum class OutputBasis { AUTOMATIC, CORRECTED, REASSESSED }
@Serializable enum class ReportDimension { ALL, ORIGIN, FORMAT, ANALYTE, LAYOUT, PARTITION }

/** Corrected output must be an explicit, separately evaluated snapshot, never an edit to a run. */
data class ReportSample(
    val case: CorpusCase,
    val truth: GroundTruthSnapshot,
    val run: ProcessingRun,
    val observations: List<Observation>,
    val references: Map<String, ReferenceRange>,
    val assessments: Map<String, Assessment>,
    val basis: OutputBasis = run.outputBasis,
    val correctionIds: List<String> = run.correctionIds,
)

@Serializable data class ReportFraction(val numerator: Long, val denominator: Long) {
    init { require(denominator >= 0 && numerator in 0..denominator) }
    val ratio: Double? get() = if (denominator == 0L) null else numerator.toDouble() / denominator
}

/** Versions and output origin are cohort keys: results from different configurations never merge. */
@Serializable data class ReportCohort(
    val mode: ExtractionMode, val basis: OutputBasis,
    val extractorVersion: String, val parserVersion: String, val ruleVersion: String,
    val truthVersion: String, val truthSchema: String,
    val inference: InferenceConfiguration?,
    val comparatorVersion: String = ExtractionComparator.VERSION,
    val isolatedVersion: String = IsolatedEvaluatorMeasurement.VERSION,
    val isolatedRuleVersion: String = br.com.exames.domain.evaluator.DeterministicRangeEvaluator.RULE_VERSION,
)

@Serializable data class ReportIdentity(
    val caseId: String, val familyId: String, val documentSha256: String,
    val runId: String, val state: RunState, val truthVersion: String, val truthSha256: String,
    val truthSchema: String, val reviewed: Boolean, val deviceModel: String, val androidVersion: String,
    val cohort: ReportCohort, val correctionIds: List<String>,
)

@Serializable data class ReportCounts(
    val samples: Int, val families: Int, val pendingSamples: Int, val incompleteSamples: Int,
    val reviewedExpected: Int, val pendingExpected: Int, val actualObservations: Int,
    val matched: Int, val missing: Int, val extra: Int, val unresolvedExpected: Int,
    val rawAccuracy: ReportFraction, val canonicalAccuracy: ReportFraction,
    val fields: Map<String, ReportFraction>, val classificationAccuracy: ReportFraction,
    val numericCoverage: ReportFraction, val matchedStatusCounts: Map<String, Int>,
    val extraStatusCounts: Map<String, Int>, val missingAssessments: Int,
    val abnormalCounts: Map<String, Int>, val isolatedStatusAccuracy: ReportFraction,
    val isolatedReasonAccuracy: ReportFraction, val isolatedUnavailable: Map<String, Int>,
)

@Serializable data class ReportRow(val cohort: ReportCohort, val dimension: ReportDimension,
    val value: String, val counts: ReportCounts)

@Serializable data class LocalValidationReport(
    val schemaVersion: String = "validation-report/1",
    val identities: List<ReportIdentity>, val rows: List<ReportRow>,
    val publicCoverageAvailable: Boolean,
)

/** Local, pure aggregation. Contains no raw fields. Serialize only in memory or protected storage. */
class ValidationReportGenerator {
    private data class Entry(val sample: ReportSample, val metrics: ValidationMetrics, val isolated: IsolatedEvaluatorReport)
    private data class Key(val cohort: ReportCohort, val dimension: ReportDimension, val value: String)
    private fun cohort(sample: ReportSample) = sample.run.let {
        ReportCohort(it.mode, sample.basis, it.extractorVersion, it.parserVersion, it.ruleVersion,
            sample.truth.groundTruth.version, sample.truth.groundTruth.schemaVersion, it.inference)
    }

    fun generate(samples: List<ReportSample>): LocalValidationReport {
        require(samples.map { Triple(it.run.runId, it.truth.sha256, it.basis) }.distinct().size == samples.size) { "DUPLICATE_REPORT_SAMPLE" }
        require(samples.groupBy { it.case.familyId }.values.all { family ->
            family.map { it.case.partition }.distinct().size == 1
        }) { "FAMILY_PARTITION_LEAK" }
        samples.forEach(::validate)
        val groups = linkedMapOf<Key, MutableList<Entry>>()
        for (sample in samples) {
            fun add(dimension: ReportDimension, value: String, subset: ReportSample = sample) {
                val metrics = ValidationMetricsCalculator().compute(subset.truth.groundTruth, subset.observations,
                    subset.references, subset.assessments, subset.run.state == RunState.COMPLETE)
                val isolated = IsolatedEvaluatorMeasurement().measure(subset.truth.groundTruth)
                groups.getOrPut(Key(cohort(sample), dimension, value)) { mutableListOf() } += Entry(subset, metrics, isolated)
            }
            add(ReportDimension.ALL, "ALL")
            add(ReportDimension.ORIGIN, sample.case.origin.name)
            add(ReportDimension.FORMAT, sample.case.format.name)
            add(ReportDimension.LAYOUT, "${sample.case.layoutId}/${sample.case.layoutVersion}")
            add(ReportDimension.PARTITION, sample.case.partition.name)
            // Analyte subsets use identity/evidence, never the expected numeric result.
            for (analyte in Analyte.entries) add(ReportDimension.ANALYTE, analyte.name, subset(sample, analyte))
            add(ReportDimension.ANALYTE, "UNSUPPORTED", subset(sample, null))
        }
        // Empty origin/format/partition strata remain visible, including zero public coverage.
        for (configuration in samples.map(::cohort).distinct()) {
            fun empty(dimension: ReportDimension, values: List<String>) = values.forEach {
                groups.getOrPut(Key(configuration, dimension, it)) { mutableListOf() }
            }
            empty(ReportDimension.ORIGIN, CorpusOrigin.entries.map { it.name })
            empty(ReportDimension.FORMAT, CorpusFormat.entries.map { it.name })
            empty(ReportDimension.PARTITION, CorpusPartition.entries.map { it.name })
        }
        val identities = samples.map { sample -> ReportIdentity(sample.case.caseId, sample.case.familyId,
            sample.case.documentSha256, sample.run.runId, sample.run.state, sample.truth.groundTruth.version,
            sample.truth.sha256, sample.truth.groundTruth.schemaVersion, sample.truth.groundTruth.hasReviewedMetadata(),
            sample.run.deviceModel, sample.run.androidVersion, cohort(sample), sample.correctionIds.sorted()) }
        return LocalValidationReport(identities = identities,
            rows = groups.map { (key, entries) -> ReportRow(key.cohort, key.dimension, key.value, aggregate(entries)) },
            publicCoverageAvailable = samples.any { it.case.origin == CorpusOrigin.PUBLIC_DEMONSTRATION &&
                it.truth.groundTruth.hasReviewedMetadata() && it.truth.groundTruth.observations.isNotEmpty() })
    }

    private fun validate(sample: ReportSample) {
        require(sample.run.state in setOf(RunState.COMPLETE, RunState.PARTIAL, RunState.FAILED, RunState.CANCELLED)) { "RUN_STILL_ACTIVE" }
        require(sample.case.caseId == sample.truth.groundTruth.caseId && sample.run.caseId == sample.case.caseId &&
            sample.case.documentSha256 == sample.run.documentSha256) { "REPORT_SOURCE_MISMATCH" }
        require(sample.observations.all { it.documentId == sample.run.documentId }) { "REPORT_DOCUMENT_MISMATCH" }
        val ids = sample.observations.map { it.id }.toSet()
        require(ids.size == sample.observations.size && sample.truth.groundTruth.observations.map { it.observationId }.distinct().size ==
            sample.truth.groundTruth.observations.size) { "REPORT_DUPLICATE_OBSERVATION" }
        require(sample.references.all { (id, value) -> id in ids && value.observationId == id } &&
            sample.assessments.all { (id, value) -> id in ids && value.observationId == id && value.ruleVersion == sample.run.ruleVersion }) {
            "REPORT_OUTPUT_OWNERSHIP_MISMATCH"
        }
        require(sample.correctionIds.all { it.isNotBlank() } && sample.correctionIds.distinct().size == sample.correctionIds.size)
        require((sample.basis == OutputBasis.CORRECTED) == sample.correctionIds.isNotEmpty()) { "REPORT_OUTPUT_BASIS_MISMATCH" }
        require(sample.basis == sample.run.outputBasis && sample.correctionIds == sample.run.correctionIds) { "REPORT_RUN_BASIS_MISMATCH" }
        if (sample.case.origin == CorpusOrigin.PUBLIC_DEMONSTRATION) {
            val provenance = requireNotNull(sample.case.provenance) { "PUBLIC_PROVENANCE_MISSING" }
            require(provenance.eligibilityReviewed && !provenance.containsIdentifiableData &&
                provenance.url.isNotBlank() && provenance.reuseTerms.isNotBlank()) { "PUBLIC_PROVENANCE_INELIGIBLE" }
        }
    }

    private fun subset(sample: ReportSample, analyte: Analyte?): ReportSample {
        val observations = sample.observations.filter { BiochemistryParser.resolve(it.analyteRaw) == analyte }
        val ids = observations.map { it.id }.toSet()
        return sample.copy(truth = sample.truth.copy(groundTruth = sample.truth.groundTruth.copy(
            observations = sample.truth.groundTruth.observations.filter { it.analyte == analyte })),
            observations = observations, references = sample.references.filterKeys { it in ids },
            assessments = sample.assessments.filterKeys { it in ids })
    }

    private fun aggregate(entries: List<Entry>): ReportCounts {
        val metrics = entries.map { it.metrics }
        fun sum(selector: (ValidationMetrics) -> Int) = metrics.sumOf(selector)
        fun fraction(selector: (ValidationMetrics) -> MetricFraction) = ReportFraction(
            metrics.sumOf { selector(it).numerator }, metrics.sumOf { selector(it).denominator })
        fun statuses(selector: (ValidationMetrics) -> Map<AssessmentStatus, Int>) = AssessmentStatus.entries.associate {
            status -> status.name to metrics.sumOf { selector(it)[status] ?: 0 }
        }
        val isolated = entries.map { it.isolated }
        return ReportCounts(entries.size, entries.map { it.sample.case.familyId }.distinct().size,
            entries.count { !it.sample.truth.groundTruth.hasReviewedMetadata() },
            metrics.count { !it.processingComplete }, sum { it.reviewedExpected }, sum { it.pendingExpected },
            sum { it.actualObservations }, sum { it.matched }, sum { it.missing }, sum { it.extra }, sum { it.unresolvedExpected },
            fraction { it.rawAccuracy }, fraction { it.canonicalAccuracy },
            metrics.flatMap { it.fieldAccuracy.keys }.distinct().associateWith { name -> ReportFraction(
                metrics.sumOf { it.fieldAccuracy[name]?.numerator ?: 0 }, metrics.sumOf { it.fieldAccuracy[name]?.denominator ?: 0 }) },
            fraction { it.classificationAccuracy }, fraction { it.numericCoverage }, statuses { it.matchedStatusCounts },
            statuses { it.extraStatusCounts }, sum { it.missingAssessments },
            mapOf("expected" to sum { it.abnormal.expected }, "correctDirection" to sum { it.abnormal.correctDirection },
                "wrongDirection" to sum { it.abnormal.wrongDirection }, "withinRange" to sum { it.abnormal.withinRange },
                "needsReview" to sum { it.abnormal.needsReview }, "notClassifiable" to sum { it.abnormal.notClassifiable },
                "missingObservation" to sum { it.abnormal.missingObservation }, "missingAssessment" to sum { it.abnormal.missingAssessment },
                "notSignaled" to sum { it.abnormal.notSignaled }),
            ReportFraction(isolated.sumOf { it.statusAccuracy.numerator }, isolated.sumOf { it.statusAccuracy.denominator }),
            ReportFraction(isolated.sumOf { it.reasonAccuracy.numerator }, isolated.sumOf { it.reasonAccuracy.denominator }),
            IsolationUnavailable.entries.associate { reason -> reason.name to isolated.sumOf { report -> report.observations.count { it.unavailable == reason } } })
    }
}
