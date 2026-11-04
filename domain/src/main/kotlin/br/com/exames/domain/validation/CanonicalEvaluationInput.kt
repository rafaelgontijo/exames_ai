package br.com.exames.domain.validation

import br.com.exames.domain.model.*
import kotlinx.serialization.Serializable

@Serializable enum class ReferenceSelection { ABSENT, SELECTED, UNRESOLVED }

/** Values/bounds of a selected reference remain solely in ExpectedObservation. */
@Serializable
data class CanonicalReferenceMetadata(val observationId: String, val sourcePage: Int,
    val unit: String?, val comparator: ReferenceComparator, val lowInclusive: Boolean,
    val highInclusive: Boolean, val applicability: Applicability, val condition: String?) {
    init { require(observationId.isNotBlank() && sourcePage in 1..20) }
}

@Serializable
data class CanonicalReferenceCandidate(val referenceRaw: String, val low: String?, val high: String?,
    val metadata: CanonicalReferenceMetadata)

/** Independently authored fixture input. Expected answers are deliberately absent. */
@Serializable
data class CanonicalEvaluationInput(val ruleVersion: String, val resultComparator: ResultComparator,
    val ambiguities: Set<Ambiguity>, val referenceSelection: ReferenceSelection,
    val selectedReference: CanonicalReferenceMetadata?, val candidates: List<CanonicalReferenceCandidate>) {
    init {
        require(ruleVersion.isNotBlank())
        require(candidates.size <= 20)
        require((referenceSelection == ReferenceSelection.SELECTED) == (selectedReference != null))
        if (referenceSelection == ReferenceSelection.ABSENT) require(candidates.isEmpty())
        if (referenceSelection == ReferenceSelection.UNRESOLVED) {
            require(candidates.isNotEmpty())
            require(Ambiguity.CONTEXT in ambiguities || Ambiguity.REFERENCE_ASSOCIATION in ambiguities)
        }
    }
}
