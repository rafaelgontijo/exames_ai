package br.com.exames.domain.usecase

import br.com.exames.domain.evaluator.ConservativeNumberParser
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.validation.*
import java.time.Clock
import java.time.Instant

fun correctionValue(observation: Observation, reference: ReferenceRange?, field: CorrectionField): String? = when (field) {
    CorrectionField.ANALYTE -> observation.analyteRaw
    CorrectionField.RESULT -> observation.resultRaw
    CorrectionField.UNIT -> observation.unitRaw
    CorrectionField.REFERENCE -> observation.referenceRaw ?: reference?.referenceRaw
    CorrectionField.APPLICABILITY -> reference?.applicability?.name
}

class SaveExtractionCorrection(private val repository: ValidationRepository, private val clock: Clock = Clock.systemUTC()) {
    suspend fun save(runId: String, observationId: String, field: CorrectionField, expectedOld: String?, value: String?, id: String) {
        require(ProcessingRunIds.isOpaque(id)) { "CORRECTION_ID_INVALID" }
        require(value == null || value.length <= 4096) { "CORRECTION_VALUE_LIMIT" }
        if (field == CorrectionField.ANALYTE || field == CorrectionField.RESULT) require(!value.isNullOrBlank()) { "CORRECTION_VALUE_REQUIRED" }
        if (field == CorrectionField.APPLICABILITY) require(value == null || Applicability.entries.any { it.name == value }) { "CORRECTION_APPLICABILITY_INVALID" }
        val run = requireNotNull(repository.findRun(runId)) { "RUN_UNAVAILABLE" }
        require(run.state !in setOf(RunState.QUEUED, RunState.RUNNING)) { "RUN_STILL_ACTIVE" }
        require(repository.observations(runId).any { it.id == observationId }) { "CORRECTION_OWNER_MISMATCH" }
        repository.appendCorrection(ManualCorrection(id, observationId, field, expectedOld, value, Instant.now(clock)))
    }
}

/** Applies audit fields, never expected answers. Clinical/source ambiguities are retained across human edits. */
class CorrectedObservationProjection {
    fun project(original: Observation, reference: ReferenceRange?, assessment: Assessment?, corrections: List<ManualCorrection>, id: String): Pair<Observation, ReferenceRange?> {
        require(corrections.all { it.observationId == original.id } && corrections.map { it.id }.distinct().size == corrections.size)
        val values = CorrectionField.entries.associateWith { correctionValue(original, reference, it) }.toMutableMap()
        for (correction in corrections) {
            require(values[correction.field] == correction.oldValue) { "CORRECTION_CHAIN_MISMATCH" }
            values[correction.field] = correction.newValue
        }
        val changed = corrections.map { it.field }.toSet()
        val ambiguities = original.ambiguities.toMutableSet()
        when (assessment?.reasonCode) {
            ReasonCode.AMBIGUOUS_INPUT -> if (ambiguities.isEmpty()) ambiguities += Ambiguity.VALUE
            ReasonCode.CENSORED_RESULT -> ambiguities += Ambiguity.COMPARATOR
            ReasonCode.CONTEXT_UNRESOLVED -> ambiguities += Ambiguity.CONTEXT
            ReasonCode.REFERENCE_MISMATCH, ReasonCode.INVALID_REFERENCE -> ambiguities += Ambiguity.REFERENCE_ASSOCIATION
            ReasonCode.UNIT_UNRESOLVED -> ambiguities += Ambiguity.UNIT
            else -> Unit
        }
        if (reference != null && reference.applicability != Applicability.APPLICABLE) ambiguities += Ambiguity.CONTEXT
        if (reference != null && (original.unitRaw.isNullOrBlank() || reference.unit.isNullOrBlank() ||
                original.unitRaw.trim() != reference.unit.trim())) ambiguities += Ambiguity.UNIT
        if (original.resultComparator != ResultComparator.EQ) ambiguities += Ambiguity.COMPARATOR
        val analyte = requireNotNull(values[CorrectionField.ANALYTE])
        val normalized = BiochemistryParser.resolve(analyte)
        if (normalized == null || normalized != BiochemistryParser.resolve(original.analyteRaw)) ambiguities += Ambiguity.REFERENCE_ASSOCIATION
        val result = requireNotNull(values[CorrectionField.RESULT])
        val number = ConservativeNumberParser().parse(result)
        val qualitative = result.isNotBlank() && result.all { it.isLetter() || it.isWhitespace() }
        if (number.value == null && !qualitative) ambiguities += Ambiguity.VALUE
        val unit = values[CorrectionField.UNIT]
        if (unit.isNullOrBlank()) ambiguities += Ambiguity.UNIT
        val rawReference = values[CorrectionField.REFERENCE]
        var candidates = original.referenceCandidates.map { it.copy(observationId = id) }
        var selected = reference?.copy(observationId = id)
        if (CorrectionField.REFERENCE in changed) {
            candidates = BiochemistryParser().parseReferences(id, reference?.sourcePage ?: original.page, rawReference.orEmpty())
            selected = candidates.singleOrNull()?.takeIf { it.applicability == Applicability.APPLICABLE }
            // A typed range does not establish document association or resolve an absent/invalid source.
            if (rawReference != correctionValue(original, reference, CorrectionField.REFERENCE)) ambiguities += Ambiguity.REFERENCE_ASSOCIATION
        }
        if (candidates.size > 1) { ambiguities += Ambiguity.REFERENCE_ASSOCIATION; selected = null }
        if (candidates.any { it.applicability == Applicability.UNKNOWN }) ambiguities += Ambiguity.CONTEXT
        if (CorrectionField.APPLICABILITY in changed) {
            val applicability = Applicability.entries.firstOrNull { it.name == values[CorrectionField.APPLICABILITY] } ?: Applicability.UNKNOWN
            selected = selected?.copy(applicability = applicability)
            if (reference?.applicability != Applicability.APPLICABLE || applicability != Applicability.APPLICABLE) ambiguities += Ambiguity.CONTEXT
        }
        val output = original.copy(id = id, analyteRaw = analyte, analyteNormalized = normalized?.name,
            resultRaw = result, numericValue = number.value, resultComparator = if (qualitative) ResultComparator.EQ else number.comparator,
            unitRaw = unit, unitNormalized = null, referenceRaw = rawReference, referenceCandidates = candidates,
            ambiguities = ambiguities) // confirmed and raw source provenance are not fabricated.
        return output to selected
    }
    companion object { const val VERSION = "manual-fields/1" }
}

class ReevaluateRun(private val repository: ValidationRepository, private val environment: ProcessingEnvironment,
    private val clock: Clock = Clock.systemUTC()) {
    suspend fun reevaluate(parentId: String, runId: String): ProcessingRun {
        require(ProcessingRunIds.isOpaque(runId) && runId != parentId) { "REEVALUATION_ID_INVALID" }
        repository.findRun(runId)?.let {
            require(it.parentRunId == parentId && it.outputBasis != OutputBasis.AUTOMATIC) { "REEVALUATION_ID_CONFLICT" }
            return it
        }
        val parent = requireNotNull(repository.findRun(parentId)) { "REEVALUATION_SOURCE_UNAVAILABLE" }
        require(parent.state !in setOf(RunState.QUEUED, RunState.RUNNING)) { "RUN_STILL_ACTIVE" }
        val document = requireNotNull(repository.find(parent.documentId)) { "REEVALUATION_SOURCE_UNAVAILABLE" }
        require(document.hash == parent.documentSha256) { "REEVALUATION_SOURCE_MISMATCH" }
        val count = requireNotNull(document.pageCount) { "REEVALUATION_PAGES_UNKNOWN" }
        require(count in 1..20)
        val originals = repository.observations(parentId)
        val assessments = repository.assessments(parentId).associateBy { it.observationId }
        val checkpoints = repository.checkpoints(parentId).associateBy { it.page }
        val ids = parent.correctionIds.toMutableList()
        val outputs = originals.map { original ->
            val corrections = repository.corrections(original.id)
            ids += corrections.map { it.id }
            CorrectedObservationProjection().project(original, repository.reference(original.id), assessments[original.id], corrections,
                StableIds.of("reevaluated-observation", runId, original.id))
        }.toMutableList()
        outputs.groupBy { it.first.page to it.first.analyteNormalized }.values.filter { it.size > 1 }.flatten().forEach { duplicate ->
            val at = outputs.indexOf(duplicate)
            outputs[at] = duplicate.first.copy(ambiguities = duplicate.first.ambiguities + Ambiguity.REFERENCE_ASSOCIATION) to duplicate.second
        }
        val evaluator = DeterministicRangeEvaluator(clock)
        val batches = (1..count).map { page ->
            val complete = checkpoints[page]?.state == PageState.COMPLETE
            val rows = if (complete) outputs.filter { it.first.page == page } else emptyList()
            PageBatch(PageCheckpoint(runId, page, parent.mode, if (complete) PageState.COMPLETE else PageState.FAILED, null,
                if (complete) null else "REEVALUATION_SOURCE_PAGE_INCOMPLETE", InferenceOutcome.NOT_ATTEMPTED),
                rows.map { it.first }, rows.mapNotNull { it.second }, rows.map { evaluator.evaluate(it.first, it.second) })
        }
        val completed = batches.count { it.checkpoint.state == PageState.COMPLETE }
        val state = if (parent.state == RunState.COMPLETE && completed == count) RunState.COMPLETE else if (completed > 0) RunState.PARTIAL else RunState.FAILED
        val child = parent.copy(runId = runId, parentRunId = parentId, state = state, ruleVersion = DeterministicRangeEvaluator.RULE_VERSION,
            startedAt = Instant.now(clock).toString(), deviceModel = environment.deviceModel, androidVersion = environment.androidVersion,
            inferenceOutcome = InferenceOutcome.NOT_ATTEMPTED, failure = null, correctionIds = ids.distinct(),
            outputBasis = if (ids.isEmpty()) OutputBasis.REASSESSED else OutputBasis.CORRECTED, projectionVersion = CorrectedObservationProjection.VERSION)
        repository.commitReevaluation(child, batches)
        return requireNotNull(repository.findRun(runId))
    }
}
