package br.com.exames.domain.inference

import br.com.exames.domain.model.Ambiguity
import br.com.exames.domain.parser.*

@kotlinx.serialization.Serializable
enum class ModelEvidenceIssue { UNKNOWN_SPAN, UNSUPPORTED_TEXT, UNKNOWN_GEOMETRY, UNSUPPORTED_ANALYTE,
    WRONG_ASSOCIATION, DIVERGENCE, DUPLICATE_OBSERVATION, MISSING_OBSERVATION, SOURCE_MISMATCH }
data class EvidenceValidation(val observations: List<ParsedObservation>, val issues: Set<ModelEvidenceIssue>,
    val acceptedProposal: FieldProposal?)

/** A model may corroborate extraction, never erase ambiguity or silently replace another row. */
class ProposalEvidenceValidator {
    fun validate(prompt: CompiledProposalPrompt, proposal: FieldProposal, baseline: ParsedPage): EvidenceValidation {
        val issues = mutableSetOf<ModelEvidenceIssue>()
        val source = prompt.evidence.entries.toList()
        val positions = source.mapIndexed { index, entry -> entry.key to index }.toMap()
        val matched = mutableSetOf<String>()
        fun spans(field: ProposedField): List<SourceFragment> {
            if (field.evidenceIds.isEmpty() || field.evidenceIds.distinct().size != field.evidenceIds.size ||
                field.raw.isBlank()) issues += ModelEvidenceIssue.UNSUPPORTED_TEXT
            val cited = field.evidenceIds.mapNotNull { prompt.evidence[it] }
            if (cited.size != field.evidenceIds.size) issues += ModelEvidenceIssue.UNKNOWN_SPAN
            if (cited.any { it.region == null }) issues += ModelEvidenceIssue.UNKNOWN_GEOMETRY
            val indices = field.evidenceIds.mapNotNull { positions[it] }
            if (indices.isEmpty() || indices != indices.sorted()) {
                issues += ModelEvidenceIssue.UNSUPPORTED_TEXT; return cited
            }
            val selected = field.evidenceIds.toSet()
            val covered = source.subList(indices.first(), indices.last() + 1)
            if (covered.any { it.key !in selected && it.value.raw.isNotBlank() }) issues += ModelEvidenceIssue.UNSUPPORTED_TEXT
            val raw = StringBuilder()
            for ((_, fragment) in covered) {
                if (fragment.spaceBefore == true && raw.isNotEmpty() && !raw.last().isWhitespace() &&
                    fragment.raw.firstOrNull()?.isWhitespace() == false) raw.append(' ')
                raw.append(fragment.raw)
            }
            if (spaces(raw.toString()) != spaces(field.raw)) issues += ModelEvidenceIssue.UNSUPPORTED_TEXT
            return cited
        }
        fun belongsTo(span: SourceFragment, original: br.com.exames.domain.model.Observation, name: String): Boolean {
            val matches = original.fieldEvidence.filter { it.field == name && it.spanId == span.spanId }
            if (matches.any { it.raw != span.raw || it.region != span.region || it.method != span.method ||
                    it.extractorVersion != span.extractorVersion || it.confidence != span.confidence })
                issues += ModelEvidenceIssue.SOURCE_MISMATCH
            return matches.any { it.raw == span.raw && it.region == span.region && it.method == span.method &&
                it.extractorVersion == span.extractorVersion && it.confidence == span.confidence }
        }
        for (candidate in proposal.observations) {
            val analyte = BiochemistryParser.resolve(candidate.analyte.raw)
            if (analyte == null) issues += ModelEvidenceIssue.UNSUPPORTED_ANALYTE
            val analyteSpans = spans(candidate.analyte)
            val candidates = baseline.observations.filter { parsed ->
                parsed.observation.analyteNormalized == analyte?.name && analyteSpans.isNotEmpty() &&
                    analyteSpans.all { span -> belongsTo(span, parsed.observation, "analyteRaw") }
            }
            val original = candidates.singleOrNull()?.observation
            if (original == null) { issues += ModelEvidenceIssue.WRONG_ASSOCIATION; continue }
            if (!matched.add(original.id)) issues += ModelEvidenceIssue.DUPLICATE_OBSERVATION
            val fields = listOf("resultRaw" to candidate.result, "unitRaw" to candidate.unit,
                "context" to candidate.context) + candidate.references.map { "referenceRaw" to it }
            for ((name, field) in fields) if (field != null) {
                val cited = spans(field)
                if (cited.isEmpty() || cited.any { span -> span.region?.page != original.page ||
                        !belongsTo(span, original, name) })
                    issues += ModelEvidenceIssue.WRONG_ASSOCIATION
            }
            val originalContext = original.fieldEvidence.filter { it.field == "context" }
            if ((candidate.context == null) != originalContext.isEmpty()) issues += ModelEvidenceIssue.DIVERGENCE
            if (candidate.context != null) {
                val citedContext = candidate.context.evidenceIds.mapNotNull { prompt.evidence[it]?.spanId }.toSet()
                if (originalContext.any { it.spanId !in citedContext }) issues += ModelEvidenceIssue.DIVERGENCE
            }
            if (spaces(candidate.analyte.raw) != spaces(original.analyteRaw) ||
                spaces(candidate.result?.raw.orEmpty()) != spaces(original.resultRaw) ||
                spaces(candidate.unit?.raw.orEmpty()) != spaces(original.unitRaw.orEmpty()) ||
                candidate.references.map { spaces(it.raw) }.sorted() != original.referenceCandidates.map { spaces(it.referenceRaw) }.sorted())
                issues += ModelEvidenceIssue.DIVERGENCE
        }
        if (matched.size != baseline.observations.size) issues += ModelEvidenceIssue.MISSING_OBSERVATION
        if (baseline.layout == null || baseline.observations.isEmpty()) issues += ModelEvidenceIssue.WRONG_ASSOCIATION
        val safe = if (issues.isEmpty()) baseline.observations else baseline.observations.map { parsed ->
            parsed.copy(observation = parsed.observation.copy(ambiguities = parsed.observation.ambiguities + Ambiguity.REFERENCE_ASSOCIATION))
        }
        return EvidenceValidation(safe, issues, proposal.takeIf { issues.isEmpty() })
    }
    private fun spaces(raw: String) = raw.trim().replace(Regex("\\s+"), " ")
}
