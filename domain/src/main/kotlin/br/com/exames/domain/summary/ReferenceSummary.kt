package br.com.exames.domain.summary

import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*

enum class SummaryCompleteness { COMPLETE, PARTIAL, IN_PROGRESS, UNAVAILABLE }

/** Review/limitation markers. Never a clinical conclusion; absence of a marker is not a guarantee. */
enum class SummaryWarning {
    NO_OBSERVATIONS, AMBIGUITIES_PRESENT, REFERENCE_MISSING, UNSUPPORTED_CONTENT, PAGES_INCOMPLETE,
    ASSESSMENT_MISSING, ORPHAN_ASSESSMENT, RUN_NOT_COMPLETE,
}

data class SummaryCounts(
    val below: Int, val within: Int, val above: Int, val needsReview: Int, val notClassifiable: Int,
) {
    val total: Int get() = below + within + above + needsReview + notClassifiable
}

/** Raw text is preserved exactly as extracted; reason is null only when no assessment exists. */
data class SummaryLine(
    val observationId: String,
    val page: Int,
    val analyteRaw: String,
    val resultRaw: String,
    val unitRaw: String?,
    val referenceRaw: String?,
    val status: AssessmentStatus,
    val reason: ReasonCode?,
    val ambiguities: Set<Ambiguity>,
    val analyteNormalized: String? = null,
)

data class ReferenceSummary(
    val runId: String,
    val documentId: String,
    val mode: ExtractionMode,
    val basis: OutputBasis,
    val inferenceOutcome: InferenceOutcome,
    val ruleVersion: String,
    val projectionVersion: String,
    val runState: RunState,
    val completeness: SummaryCompleteness,
    val pagesComplete: Int,
    val pagesExpected: Int?,
    val counts: SummaryCounts,
    val lines: List<SummaryLine>,
    val warnings: Set<SummaryWarning>,
    val parseWarningCodes: Set<String>,
    val participation: InferenceParticipation? = null,
)

/**
 * Pure projection of already persisted outputs. It never evaluates, converts units, upgrades a status
 * or fills gaps: a missing assessment counts as NEEDS_REVIEW, and an incomplete run is never COMPLETE.
 */
object ProjectReferenceSummary {
    const val VERSION = "REFERENCE_SUMMARY/1.0.0"

    fun project(
        run: ProcessingRun,
        observations: List<Observation>,
        assessments: List<Assessment>,
        checkpoints: List<PageCheckpoint>,
        expectedPages: Int?,
    ): ReferenceSummary {
        val byObservation = assessments.groupBy { it.observationId }
        val knownIds = observations.map { it.id }.toSet()
        val warnings = linkedSetOf<SummaryWarning>()
        val lines = observations.sortedWith(compareBy({ it.page }, { it.id })).map { o ->
            val a = byObservation[o.id]?.singleOrNull()
            // Duplicate assessments for one observation are ambiguous: do not pick one.
            val duplicate = (byObservation[o.id]?.size ?: 0) > 1
            if (a == null || duplicate) warnings += SummaryWarning.ASSESSMENT_MISSING
            val status = if (duplicate) AssessmentStatus.NEEDS_REVIEW else a?.status ?: AssessmentStatus.NEEDS_REVIEW
            val reason = if (duplicate) ReasonCode.AMBIGUOUS_INPUT else a?.reasonCode
            if (o.ambiguities.isNotEmpty()) warnings += SummaryWarning.AMBIGUITIES_PRESENT
            if (reason == ReasonCode.NO_REFERENCE || (o.referenceRaw == null && o.referenceCandidates.isEmpty()))
                warnings += SummaryWarning.REFERENCE_MISSING
            SummaryLine(o.id, o.page, o.analyteRaw, o.resultRaw, o.unitRaw, o.referenceRaw, status, reason, o.ambiguities, o.analyteNormalized)
        }
        if (assessments.any { it.observationId !in knownIds }) warnings += SummaryWarning.ORPHAN_ASSESSMENT
        if (lines.isEmpty()) warnings += SummaryWarning.NO_OBSERVATIONS

        val runCheckpoints = checkpoints.filter { it.runId == run.runId }
        val complete = runCheckpoints.filter { it.state == PageState.COMPLETE }.map { it.page }.toSet()
        val covered = expectedPages == null || (1..expectedPages).all { it in complete }
        val parseCodes = runCheckpoints.flatMap { it.parseWarnings }.toSortedSet()
        if (parseCodes.isNotEmpty()) warnings += SummaryWarning.UNSUPPORTED_CONTENT

        val completeness = when (run.state) {
            RunState.QUEUED, RunState.RUNNING -> SummaryCompleteness.IN_PROGRESS
            RunState.COMPLETE -> if (covered && expectedPages != null) SummaryCompleteness.COMPLETE
            else if (complete.isNotEmpty()) SummaryCompleteness.PARTIAL else SummaryCompleteness.UNAVAILABLE
            RunState.PARTIAL -> if (complete.isNotEmpty()) SummaryCompleteness.PARTIAL else SummaryCompleteness.UNAVAILABLE
            RunState.FAILED, RunState.CANCELLED -> if (complete.isNotEmpty()) SummaryCompleteness.PARTIAL else SummaryCompleteness.UNAVAILABLE
        }
        if (completeness != SummaryCompleteness.COMPLETE) warnings += SummaryWarning.PAGES_INCOMPLETE
        if (run.state != RunState.COMPLETE) warnings += SummaryWarning.RUN_NOT_COMPLETE

        fun count(s: AssessmentStatus) = lines.count { it.status == s }
        return ReferenceSummary(
            runId = run.runId, documentId = run.documentId, mode = run.mode, basis = run.outputBasis,
            inferenceOutcome = run.inferenceOutcome, ruleVersion = run.ruleVersion, projectionVersion = VERSION,
            runState = run.state, completeness = completeness, pagesComplete = complete.size, pagesExpected = expectedPages,
            counts = SummaryCounts(count(AssessmentStatus.BELOW_RANGE), count(AssessmentStatus.WITHIN_RANGE),
                count(AssessmentStatus.ABOVE_RANGE), count(AssessmentStatus.NEEDS_REVIEW), count(AssessmentStatus.NOT_CLASSIFIABLE)),
            lines = lines, warnings = warnings, parseWarningCodes = parseCodes, participation = InferenceParticipation.from(run, runCheckpoints),
        )
    }
}
