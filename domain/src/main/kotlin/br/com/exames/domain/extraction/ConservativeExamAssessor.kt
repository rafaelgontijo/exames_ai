package br.com.exames.domain.extraction

import br.com.exames.domain.evaluator.ConservativeNumberParser
import br.com.exames.domain.model.ResultComparator
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

enum class DocumentaryStatus {
    WITHIN_RANGE,
    NEEDS_REVIEW,
    NOT_CLASSIFIABLE,
}

enum class DocumentaryReason {
    WITHIN_DOCUMENT_RANGE,
    ABOVE_DOCUMENT_RANGE,
    BELOW_DOCUMENT_RANGE,
    UNCERTAIN_REFERENCE_LINK,
    MULTIPLE_OR_CONDITIONAL_REFERENCE,
    UNIT_MISMATCH_OR_MISSING,
    CENSORED_OR_AMBIGUOUS_VALUE,
    INVALID_REFERENCE_SYNTAX,
    NO_REFERENCE,
    NON_NUMERIC_RESULT,
}

data class AssessedObservation(
    val observation: ExamObservation,
    val status: DocumentaryStatus,
    val reason: DocumentaryReason,
)

/**
 * Pure deterministic, conservative evaluator for AI-extracted exam observations.
 * Never invents ranges, never converts units, and never relaxes NEEDS_REVIEW fallback.
 */
class ConservativeExamAssessor(
    private val numberParser: ConservativeNumberParser = ConservativeNumberParser(),
) {
    fun assess(observation: ExamObservation): AssessedObservation {
        fun result(status: DocumentaryStatus, reason: DocumentaryReason) =
            AssessedObservation(observation, status, reason)

        if (observation.referenceNeedsReview) {
            return result(DocumentaryStatus.NEEDS_REVIEW, DocumentaryReason.UNCERTAIN_REFERENCE_LINK)
        }
        val referenceText = observation.reference?.text?.trim()
        if (referenceText.isNullOrEmpty()) {
            return result(DocumentaryStatus.NOT_CLASSIFIABLE, DocumentaryReason.NO_REFERENCE)
        }

        val rawValue = observation.result.trim()
        if (rawValue.isEmpty()) {
            return result(DocumentaryStatus.NEEDS_REVIEW, DocumentaryReason.CENSORED_OR_AMBIGUOUS_VALUE)
        }
        if (isQualitative(rawValue)) {
            return result(DocumentaryStatus.NOT_CLASSIFIABLE, DocumentaryReason.NON_NUMERIC_RESULT)
        }

        val parsedValue = numberParser.parse(rawValue)
        val numericValue = parsedValue.value
        if (parsedValue.needsReview || numericValue == null || parsedValue.comparator != ResultComparator.EQ) {
            return result(DocumentaryStatus.NEEDS_REVIEW, DocumentaryReason.CENSORED_OR_AMBIGUOUS_VALUE)
        }

        val parsedRef = parseSingleReference(referenceText)
        if (parsedRef is RefParse.Failure) {
            return result(DocumentaryStatus.NEEDS_REVIEW, parsedRef.reason)
        }
        val bound = (parsedRef as RefParse.Single).bound

        val obsUnit = normalizeUnit(observation.unit)
        val refUnit = normalizeUnit(bound.unit)
        if (obsUnit == null || refUnit == null || obsUnit != refUnit) {
            return result(DocumentaryStatus.NEEDS_REVIEW, DocumentaryReason.UNIT_MISMATCH_OR_MISSING)
        }

        if (bound.low != null) {
            val cmp = numericValue.compareTo(bound.low)
            if (cmp < 0 || (cmp == 0 && !bound.lowInclusive)) {
                return result(DocumentaryStatus.NEEDS_REVIEW, DocumentaryReason.BELOW_DOCUMENT_RANGE)
            }
        }
        if (bound.high != null) {
            val cmp = numericValue.compareTo(bound.high)
            if (cmp > 0 || (cmp == 0 && !bound.highInclusive)) {
                return result(DocumentaryStatus.NEEDS_REVIEW, DocumentaryReason.ABOVE_DOCUMENT_RANGE)
            }
        }
        return result(DocumentaryStatus.WITHIN_RANGE, DocumentaryReason.WITHIN_DOCUMENT_RANGE)
    }

    private fun isQualitative(raw: String): Boolean {
        val hasDigit = raw.any(Char::isDigit)
        val hasComparison = raw.any { it in "<>≤≥=" }
        return !hasDigit && !hasComparison && raw.any(Char::isLetter)
    }

    private sealed interface RefParse {
        data class Single(val bound: NumericBound) : RefParse
        data class Failure(val reason: DocumentaryReason) : RefParse
    }

    private data class NumericBound(
        val low: BigDecimal?,
        val high: BigDecimal?,
        val lowInclusive: Boolean,
        val highInclusive: Boolean,
        val unit: String?,
    )

    private fun parseSingleReference(raw: String): RefParse {
        if (raw.contains('\n') || raw.contains('\r')) {
            return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)
        }
        val clean = raw.replace(Regex("[\\t ]+"), " ").trim()
        val folded = fold(clean)
        if (clean.contains(';') || clean.contains(':') || MULTI_OR_CONDITIONAL.containsMatchIn(folded)) {
            return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)
        }

        RANGE_REGEX.matchEntire(clean)?.let { m ->
            val low = parseExactNumber(m.groupValues[1]) ?: return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            val high = parseExactNumber(m.groupValues[2]) ?: return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            if (low > high) return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            val unit = m.groupValues[3].ifBlank { null }
            return RefParse.Single(NumericBound(low, high, lowInclusive = true, highInclusive = true, unit = unit))
        }

        HALF_OPEN_REGEX.matchEntire(clean)?.let { m ->
            val low = parseExactNumber(m.groupValues[1]) ?: return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            val lowInc = m.groupValues[2] != "<"
            val highInc = m.groupValues[3] != "<"
            val high = parseExactNumber(m.groupValues[4]) ?: return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            val valid = low < high || (low.compareTo(high) == 0 && lowInc && highInc)
            if (!valid) return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            val unit = m.groupValues[5].ifBlank { null }
            return RefParse.Single(NumericBound(low, high, lowInclusive = lowInc, highInclusive = highInc, unit = unit))
        }

        SYMBOLIC_UNILATERAL_REGEX.matchEntire(clean)?.let { m ->
            val op = m.groupValues[1]
            val value = parseExactNumber(m.groupValues[2]) ?: return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            val unit = m.groupValues[3].ifBlank { null }
            return when (op) {
                "<" -> RefParse.Single(NumericBound(null, value, lowInclusive = false, highInclusive = false, unit = unit))
                "<=", "≤" -> RefParse.Single(NumericBound(null, value, lowInclusive = false, highInclusive = true, unit = unit))
                ">" -> RefParse.Single(NumericBound(value, null, lowInclusive = false, highInclusive = false, unit = unit))
                ">=", "≥" -> RefParse.Single(NumericBound(value, null, lowInclusive = true, highInclusive = false, unit = unit))
                else -> RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            }
        }

        PT_UNILATERAL_REGEX.matchEntire(clean)?.let { m ->
            val phrase = fold(m.groupValues[1])
            val value = parseExactNumber(m.groupValues[2]) ?: return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            val unit = m.groupValues[3].ifBlank { null }
            return when {
                phrase == "abaixo de" || phrase == "menor que" || phrase == "inferior a" ->
                    RefParse.Single(NumericBound(null, value, lowInclusive = false, highInclusive = false, unit = unit))
                phrase == "ate" || phrase == "menor ou igual a" ->
                    RefParse.Single(NumericBound(null, value, lowInclusive = false, highInclusive = true, unit = unit))
                phrase == "acima de" || phrase == "maior que" || phrase == "superior a" ->
                    RefParse.Single(NumericBound(value, null, lowInclusive = false, highInclusive = false, unit = unit))
                phrase == "maior ou igual a" || phrase == "a partir de" ->
                    RefParse.Single(NumericBound(value, null, lowInclusive = true, highInclusive = false, unit = unit))
                else -> RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            }
        }

        return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
    }

    private fun parseExactNumber(raw: String): BigDecimal? {
        val parsed = numberParser.parse(raw)
        if (parsed.needsReview || parsed.comparator != ResultComparator.EQ) return null
        return parsed.value
    }

    private fun normalizeUnit(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed
            .replace('\u03BC', '\u00B5') // Greek mu -> micro sign
            .replace(Regex("\\s+"), " ")
            .replace(" /", "/")
            .replace("/ ", "/")
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)

    companion object {
        private const val NUM = """([+-]?[0-9]+(?:[.,][0-9]+)*)"""
        private const val UNIT = """((?:(?:[x×]\s*)?10[\^ˆ]?[+−-]?\d+\s*[/·]\s*)?[%°μµ/\p{L}][%°μµ/\p{L}\p{N}^.*·()-]*(?: [\p{L}][\p{L}\p{N}/.*·^()-]*)?)"""
        private val RANGE_REGEX = Regex("""^$NUM\s*(?:a|até|ate|–|—|-)\s*$NUM(?:\s+$UNIT)?$""", RegexOption.IGNORE_CASE)
        private val HALF_OPEN_REGEX = Regex("""^$NUM\s*(<=|≤|<)\s*x\s*(<=|≤|<)\s*$NUM(?:\s+$UNIT)?$""", RegexOption.IGNORE_CASE)
        private val SYMBOLIC_UNILATERAL_REGEX = Regex("""^(<=|>=|<|>|≤|≥)\s*$NUM(?:\s+$UNIT)?$""")
        private val PT_UNILATERAL_REGEX = Regex(
            """^(abaixo\s+de|menor\s+que|inferior\s+a|at[eé]|menor\s+ou\s+igual\s+a|acima\s+de|maior\s+que|superior\s+a|maior\s+ou\s+igual\s+a|a\s+partir\s+de)\s+$NUM(?:\s+$UNIT)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val MULTI_OR_CONDITIONAL = Regex(
            """\b(?:ou|adultos?|criancas?|homens?|mulheres?|masculino|feminino|gestantes?|trimestre|risco|desejavel|otimo|limitrofe|elevado|alto|baixo|jejum|pos-prandial|idade|anos|meses|dias|vide|conforme|ver)\b"""
        )
    }
}
