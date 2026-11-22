package br.com.exames.domain.validation

import br.com.exames.domain.model.*

data class MetricFraction(val numerator: Long, val denominator: Long) {
    init { require(denominator >= 0 && numerator in 0..denominator) }
    val ratio: Double? get() = if (denominator == 0L) null else numerator.toDouble() / denominator
}
data class AbnormalBreakdown(val expected: Int, val correctDirection: Int, val wrongDirection: Int,
    val withinRange: Int, val needsReview: Int, val notClassifiable: Int, val missingObservation: Int,
    val missingAssessment: Int) {
    val notSignaled: Int get() = withinRange + needsReview + notClassifiable + missingObservation + missingAssessment
    val shownWithinFraction: MetricFraction get() = MetricFraction(withinRange.toLong(), expected.toLong())
    val notSignaledFraction: MetricFraction get() = MetricFraction(notSignaled.toLong(), expected.toLong())
}
data class ValidationMetrics(val reviewedExpected: Int, val pendingExpected: Int, val actualObservations: Int,
    val matched: Int, val missing: Int, val extra: Int, val unresolvedExpected: Int,
    val processingComplete: Boolean, val rawAccuracy: MetricFraction, val canonicalAccuracy: MetricFraction,
    val fieldAccuracy: Map<String, MetricFraction>, val numericCoverage: MetricFraction,
    val classificationAccuracy: MetricFraction, val matchedStatusCounts: Map<AssessmentStatus, Int>,
    val extraStatusCounts: Map<AssessmentStatus, Int>, val missingAssessments: Int,
    val abnormal: AbnormalBreakdown, val comparison: ExtractionComparison?)

/** Explicit counts first. Missing outputs are incorrect; extras never raise coverage or accuracy. */
class ValidationMetricsCalculator(private val comparator: ExtractionComparator = ExtractionComparator()) {
    fun compute(truth: GroundTruthVersion, actual: List<Observation>, references: Map<String, ReferenceRange>,
        assessments: Map<String, Assessment>, processingComplete: Boolean): ValidationMetrics {
        val reviewed = truth.hasReviewedMetadata()
        if (!reviewed) return ValidationMetrics(0, truth.observations.size, actual.size, 0, 0, 0, 0,
            processingComplete, fraction(0, 0), fraction(0, 0), emptyMap(), fraction(0, 0), fraction(0, 0),
            emptyMap(), emptyMap(), 0, AbnormalBreakdown(0, 0, 0, 0, 0, 0, 0, 0), null)
        val expected = truth.observations
        val comparison = comparator.compare(expected, actual, references, assessments)
        val pairs = comparison.matching.pairs.associate { it.expectedId to it.actualId }
        val matchedIds = pairs.values.toSet()
        val statuses = matchedIds.mapNotNull { assessments[it]?.status }.groupingBy { it }.eachCount()
        val extras = comparison.matching.extra.mapNotNull { assessments[it]?.status }.groupingBy { it }.eachCount()
        val comparedFields = (comparison.raw.map { "raw." + it.field to it } + comparison.canonical.map { "canonical." + it.field to it })
            .groupBy({ it.first }, { it.second })
        val fields = FIELD_NAMES.associateWith { name -> fraction(comparedFields[name].orEmpty().count { it.matches }, expected.size) }
        var correct = 0; var numeric = 0
        var abnormal = 0; var rightDirection = 0; var wrongDirection = 0
        var within = 0; var review = 0; var unclassifiable = 0; var missingObservation = 0; var missingAssessment = 0
        for (value in expected) {
            val id = pairs[value.observationId]
            val status = id?.let { assessments[it]?.status }
            if (status?.name == value.expectedStatus.name) correct++
            if (status in NUMERIC) numeric++
            if (value.expectedStatus in ABNORMAL) {
                abnormal++
                when {
                    id == null -> missingObservation++
                    status == null -> missingAssessment++
                    status == AssessmentStatus.WITHIN_RANGE -> within++
                    status == AssessmentStatus.NEEDS_REVIEW -> review++
                    status == AssessmentStatus.NOT_CLASSIFIABLE -> unclassifiable++
                    status.name == value.expectedStatus.name -> rightDirection++
                    else -> wrongDirection++
                }
            }
        }
        return ValidationMetrics(expected.size, 0, actual.size, pairs.size, comparison.matching.missing.size,
            comparison.matching.extra.size, comparison.matching.unresolved.size, processingComplete,
            MetricFraction(comparison.raw.count { it.matches }.toLong(), expected.size.toLong() * RAW_FIELDS),
            MetricFraction(comparison.canonical.count { it.matches }.toLong(), expected.size.toLong() * CANONICAL_FIELDS),
            fields, fraction(numeric, expected.size), fraction(correct, expected.size), statuses, extras,
            matchedIds.count { it !in assessments }, AbnormalBreakdown(abnormal, rightDirection, wrongDirection,
                within, review, unclassifiable, missingObservation, missingAssessment), comparison)
    }
    private fun fraction(numerator: Int, denominator: Int) = MetricFraction(numerator.toLong(), denominator.toLong())
    companion object {
        private const val RAW_FIELDS = 4L
        private const val CANONICAL_FIELDS = 7L
        private val FIELD_NAMES = listOf("raw.analyteRaw", "raw.resultRaw", "raw.unitRaw", "raw.referenceRaw",
            "canonical.analyte", "canonical.numericValue", "canonical.unit", "canonical.low", "canonical.high",
            "canonical.referenceAssociation", "canonical.assessmentStatus")
        private val NUMERIC = setOf(AssessmentStatus.BELOW_RANGE, AssessmentStatus.WITHIN_RANGE, AssessmentStatus.ABOVE_RANGE)
        private val ABNORMAL = setOf(ExpectedStatus.BELOW_RANGE, ExpectedStatus.ABOVE_RANGE)
    }
}
