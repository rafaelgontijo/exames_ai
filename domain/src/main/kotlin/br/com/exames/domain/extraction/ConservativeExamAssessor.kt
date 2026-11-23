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
    VISUAL_EXTRACTION_WITHOUT_DIGITAL_TEXT,
}

data class AssessedObservation(
    val observation: ExamObservation,
    val status: DocumentaryStatus,
    val reason: DocumentaryReason,
    val appliedReferenceExcerpt: String? = null,
    val referenceUnitImplicit: Boolean = false,
)

/**
 * Pure deterministic, conservative evaluator for AI-extracted exam observations.
 * Never invents ranges, never converts units, and never relaxes NEEDS_REVIEW fallback.
 */
class ConservativeExamAssessor(
    private val numberParser: ConservativeNumberParser = ConservativeNumberParser(),
) {
    fun assess(
        observation: ExamObservation,
        context: ClinicalContext = ClinicalContext(),
    ): AssessedObservation {
        fun result(
            status: DocumentaryStatus,
            reason: DocumentaryReason,
            appliedRef: String? = null,
            implicitUnit: Boolean = false,
        ) = AssessedObservation(observation, status, reason, appliedRef, implicitUnit)

        if (observation.visualOnly) {
            return result(DocumentaryStatus.NEEDS_REVIEW, DocumentaryReason.VISUAL_EXTRACTION_WITHOUT_DIGITAL_TEXT)
        }
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

        val parsedRef = parseReference(referenceText, context)
        if (parsedRef is RefParse.Failure) {
            return result(DocumentaryStatus.NEEDS_REVIEW, parsedRef.reason)
        }
        val matched = parsedRef as RefParse.Single
        val bound = matched.bound

        val obsUnit = normalizeUnit(observation.unit)
        val refUnit = normalizeUnit(bound.unit)
        val implicitRefUnit = obsUnit != null && refUnit == null
        val unitsCompatible = when {
            obsUnit != null && refUnit == null -> true
            obsUnit != null && refUnit != null -> obsUnit == refUnit
            else -> false
        }
        if (!unitsCompatible) {
            return result(DocumentaryStatus.NEEDS_REVIEW, DocumentaryReason.UNIT_MISMATCH_OR_MISSING, matched.appliedExcerpt)
        }

        if (bound.low != null) {
            val cmp = numericValue.compareTo(bound.low)
            if (cmp < 0 || (cmp == 0 && !bound.lowInclusive)) {
                return result(
                    DocumentaryStatus.NEEDS_REVIEW,
                    DocumentaryReason.BELOW_DOCUMENT_RANGE,
                    matched.appliedExcerpt,
                    implicitRefUnit,
                )
            }
        }
        if (bound.high != null) {
            val cmp = numericValue.compareTo(bound.high)
            if (cmp > 0 || (cmp == 0 && !bound.highInclusive)) {
                return result(
                    DocumentaryStatus.NEEDS_REVIEW,
                    DocumentaryReason.ABOVE_DOCUMENT_RANGE,
                    matched.appliedExcerpt,
                    implicitRefUnit,
                )
            }
        }
        return result(
            DocumentaryStatus.WITHIN_RANGE,
            DocumentaryReason.WITHIN_DOCUMENT_RANGE,
            matched.appliedExcerpt,
            implicitRefUnit,
        )
    }

    private fun isQualitative(raw: String): Boolean {
        val hasDigit = raw.any(Char::isDigit)
        val hasComparison = raw.any { it in "<>≤≥=" }
        return !hasDigit && !hasComparison && raw.any(Char::isLetter)
    }

    private sealed interface RefParse {
        data class Single(val bound: NumericBound, val appliedExcerpt: String? = null) : RefParse
        data class Failure(val reason: DocumentaryReason) : RefParse
    }

    private data class NumericBound(
        val low: BigDecimal?,
        val high: BigDecimal?,
        val lowInclusive: Boolean,
        val highInclusive: Boolean,
        val unit: String?,
    )

    private data class AgeRange(
        val min: Int?,
        val max: Int?,
        val minInclusive: Boolean,
        val maxInclusive: Boolean,
    ) {
        fun contains(age: Int): Boolean {
            if (min != null) {
                if (age < min || (age == min && !minInclusive)) return false
            }
            if (max != null) {
                if (age > max || (age == max && !maxInclusive)) return false
            }
            return true
        }
    }

    private data class StratumCondition(
        val sex: BiologicalSex? = null,
        val ageRange: AgeRange? = null,
        val fasting: FastingState? = null,
        val pregnancy: PregnancyState? = null,
        val subYearOnly: Boolean = false,
        val pediatricUnspecified: Boolean = false,
        val hasUncapturedQualifier: Boolean = false,
        val explicitlyUnconditionalAxis: Boolean = false,
    ) {
        val isUnconstrained: Boolean
            get() = sex == null && ageRange == null && fasting == null && pregnancy == null &&
                !subYearOnly && !pediatricUnspecified && !hasUncapturedQualifier

        fun mergeWithParent(parent: StratumCondition): StratumCondition = StratumCondition(
            sex = sex ?: parent.sex,
            ageRange = ageRange ?: parent.ageRange,
            fasting = fasting ?: parent.fasting,
            pregnancy = pregnancy ?: parent.pregnancy,
            subYearOnly = subYearOnly || parent.subYearOnly,
            pediatricUnspecified = pediatricUnspecified || parent.pediatricUnspecified,
            hasUncapturedQualifier = hasUncapturedQualifier || parent.hasUncapturedQualifier,
            explicitlyUnconditionalAxis = explicitlyUnconditionalAxis || parent.explicitlyUnconditionalAxis,
        )

        fun evaluate(context: ClinicalContext): StratumMatch {
            // Hard exclusions first (e.g. a female-only hormonal phase is excluded for MALE).
            if (sex != null && context.sex != BiologicalSex.UNSPECIFIED && sex != context.sex) return StratumMatch.EXCLUDED
            if (pregnancy == PregnancyState.PREGNANT && context.sex == BiologicalSex.MALE) return StratumMatch.EXCLUDED
            if (pregnancy != null && context.pregnancy != PregnancyState.UNSPECIFIED && pregnancy != context.pregnancy) return StratumMatch.EXCLUDED
            if (subYearOnly && context.ageYears != null && context.ageYears >= 1) return StratumMatch.EXCLUDED
            if (pediatricUnspecified && context.ageYears != null && context.ageYears >= 18) return StratumMatch.EXCLUDED
            if (ageRange != null && context.ageYears != null && !ageRange.contains(context.ageYears)) return StratumMatch.EXCLUDED
            if (fasting != null && context.fasting != FastingState.UNSPECIFIED && fasting != context.fasting) return StratumMatch.EXCLUDED

            // Fail-closed on any uncaptured qualifier or missing required context axis.
            if (hasUncapturedQualifier || subYearOnly || pediatricUnspecified) return StratumMatch.UNKNOWN
            if (sex != null && context.sex == BiologicalSex.UNSPECIFIED) return StratumMatch.UNKNOWN
            if (ageRange != null && context.ageYears == null) return StratumMatch.UNKNOWN
            if (fasting != null && context.fasting == FastingState.UNSPECIFIED) return StratumMatch.UNKNOWN
            if (pregnancy != null && context.pregnancy == PregnancyState.UNSPECIFIED) return StratumMatch.UNKNOWN
            if (isUnconstrained && !explicitlyUnconditionalAxis) return StratumMatch.UNKNOWN
            return StratumMatch.MATCH
        }
    }

    private enum class StratumMatch { MATCH, EXCLUDED, UNKNOWN }

    private fun parseReference(raw: String, context: ClinicalContext): RefParse {
        val single = parseSingleReference(raw)
        if (single is RefParse.Single) return single
        if (single is RefParse.Failure && single.reason == DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE && !context.isEmpty) {
            return parseStratifiedReference(raw, context)
        }
        return single
    }

    private fun parseSingleReference(raw: String): RefParse {
        if (raw.contains('\n') || raw.contains('\r')) {
            return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)
        }
        val clean = REF_HEADER_PREFIX.replace(raw.replace(Regex("[\\t ]+"), " ").trim(), "").trim()
        val folded = fold(clean)
        if (clean.contains(';') || clean.contains(':') || MULTI_OR_CONDITIONAL.containsMatchIn(folded)) {
            return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)
        }
        return parseBoundOnly(clean)
    }

    private fun parseBoundOnly(clean: String): RefParse {
        RANGE_REGEX.matchEntire(clean)?.let { m ->
            val low = parseExactNumber(m.groupValues[1]) ?: return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            val high = parseExactNumber(m.groupValues[2]) ?: return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            if (low > high) return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            val unit = m.groupValues[3].ifBlank { null }
            return RefParse.Single(NumericBound(low, high, lowInclusive = true, highInclusive = true, unit = unit))
        }

        BETWEEN_RANGE_REGEX.matchEntire(clean)?.let { m ->
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
                phrase == "ate" || phrase == "menor ou igual a" || phrase == "inferior ou igual a" ->
                    RefParse.Single(NumericBound(null, value, lowInclusive = false, highInclusive = true, unit = unit))
                phrase == "acima de" || phrase == "maior que" || phrase == "superior a" ->
                    RefParse.Single(NumericBound(value, null, lowInclusive = false, highInclusive = false, unit = unit))
                phrase == "maior ou igual a" || phrase == "superior ou igual a" || phrase == "a partir de" ->
                    RefParse.Single(NumericBound(value, null, lowInclusive = true, highInclusive = false, unit = unit))
                else -> RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
            }
        }

        return RefParse.Failure(DocumentaryReason.INVALID_REFERENCE_SYNTAX)
    }

    private fun parseStratifiedReference(raw: String, context: ClinicalContext): RefParse {
        val segments = splitStrataSegments(raw)
        if (segments.isEmpty()) return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)

        var groupContext = StratumCondition()
        val matched = mutableListOf<RefParse.Single>()
        var parsedClauses = 0

        for (rawSegment in segments) {
            val segment = REF_HEADER_PREFIX.replace(rawSegment.replace(Regex("[\\t ]+"), " ").trim(), "").trim()
            if (segment.isEmpty()) continue
            val foldedSeg = fold(segment)
            if (PURE_REF_HEADER.matches(foldedSeg)) continue

            val colonIdx = segment.lastIndexOf(':')
            if (colonIdx >= 0) {
                val label = segment.substring(0, colonIdx).trim()
                val rhs = segment.substring(colonIdx + 1).trim()
                if (rhs.isEmpty()) {
                    // Section header (e.g., "Sexo masculino:" or "Adultos:")
                    val headerCond = parseCondition(label)
                    groupContext = if (headerCond.sex != null) {
                        headerCond
                    } else {
                        headerCond.mergeWithParent(groupContext)
                    }
                    continue
                }
                val boundParse = parseBoundOnly(rhs)
                if (boundParse !is RefParse.Single) {
                    return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)
                }
                val ownCondition = parseCondition(label)
                // If this labeled clause starts a new sex group (e.g., "Sexo masculino: < 18 anos: ..."), update group sex.
                if (ownCondition.sex != null) {
                    groupContext = StratumCondition(sex = ownCondition.sex)
                }
                val effectiveCondition = if (ownCondition.sex != null) ownCondition else ownCondition.mergeWithParent(groupContext)
                parsedClauses++
                when (effectiveCondition.evaluate(context)) {
                    StratumMatch.MATCH -> matched += boundParse.copy(appliedExcerpt = segment)
                    StratumMatch.EXCLUDED -> Unit
                    StratumMatch.UNKNOWN -> return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)
                }
            } else {
                // Segment without ':' — try inline condition + bound (e.g. "Adultos < 8 mg/L" or "Homens 13,5 a 17,5 g/dL")
                val inline = parseInlineConditionAndBound(segment)
                    ?: return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)
                val effectiveCondition = if (inline.first.sex != null) inline.first else inline.first.mergeWithParent(groupContext)
                parsedClauses++
                when (effectiveCondition.evaluate(context)) {
                    StratumMatch.MATCH -> matched += inline.second.copy(appliedExcerpt = segment)
                    StratumMatch.EXCLUDED -> Unit
                    StratumMatch.UNKNOWN -> return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)
                }
            }
        }

        if (parsedClauses == 0 || matched.size != 1) {
            return RefParse.Failure(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE)
        }
        return matched.single()
    }

    private fun splitStrataSegments(raw: String): List<String> {
        val out = mutableListOf<String>()
        for (line in raw.split(Regex("""\r?\n|;"""))) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val commaSplit = trimmed.split(COMMA_LABELED_STRATUM_SPLIT)
            for (part in commaSplit) {
                val p = part.trim()
                val spaceSplit = if (p.count { it == ':' } >= 2) p.split(SPACE_LABELED_STRATUM_SPLIT) else listOf(p)
                for (sub in spaceSplit) {
                    val clean = sub.trim()
                    if (clean.isNotEmpty()) out += clean
                }
            }
        }
        return out
    }

    private fun parseInlineConditionAndBound(segment: String): Pair<StratumCondition, RefParse.Single>? {
        // Pattern 1: "Bound (Condition)" — e.g. "Inferior a 190 mg/dL (Adultos acima de 20 anos)"
        TRAILING_PAREN_CONDITION.matchEntire(segment)?.let { pm ->
            val boundPart = pm.groupValues[1].trim()
            val labelPart = pm.groupValues[2].trim()
            val boundParse = parseBoundOnly(boundPart) as? RefParse.Single
            if (boundParse != null && labelPart.isNotEmpty()) {
                return parseCondition(labelPart) to boundParse
            }
        }
        // Pattern 2: "Condition Bound" — e.g. "Adultos < 8 mg/L" or "Homens 13,5 a 17,5 g/dL"
        val m = INLINE_BOUND_START.find(segment) ?: return null
        val label = segment.substring(0, m.range.first).trim()
        val rhs = segment.substring(m.range.first).trim()
        if (label.isEmpty() || rhs.isEmpty()) return null
        val boundParse = parseBoundOnly(rhs) as? RefParse.Single ?: return null
        return parseCondition(label) to boundParse
    }

    private fun parseCondition(label: String): StratumCondition {
        var rem = fold(label)
        rem = REF_HEADER_PREFIX.replace(rem, " ")

        var sex: BiologicalSex? = null
        var ageRange: AgeRange? = null
        var fasting: FastingState? = null
        var pregnancy: PregnancyState? = null
        var subYearOnly = false
        var pediatricUnspecified = false
        var explicitlyUnconditionalAxis = false

        // 1. Sex
        val hasMale = MALE_REGEX.containsMatchIn(rem)
        val hasFemale = FEMALE_REGEX.containsMatchIn(rem)
        if (hasMale && !hasFemale) sex = BiologicalSex.MALE
        else if (hasFemale && !hasMale) sex = BiologicalSex.FEMALE
        else if (hasMale && hasFemale) explicitlyUnconditionalAxis = true
        rem = MALE_REGEX.replace(rem, " ")
        rem = FEMALE_REGEX.replace(rem, " ")

        // 2. Pregnancy (check negative before positive)
        if (NOT_PREGNANT_REGEX.containsMatchIn(rem)) {
            pregnancy = PregnancyState.NOT_PREGNANT
            rem = NOT_PREGNANT_REGEX.replace(rem, " ")
        } else if (PREGNANT_REGEX.containsMatchIn(rem)) {
            pregnancy = PregnancyState.PREGNANT
            rem = PREGNANT_REGEX.replace(rem, " ")
        }

        // 3. Fasting (check "com ou sem jejum" before specific fasting states)
        if (ANY_FASTING_REGEX.containsMatchIn(rem)) {
            explicitlyUnconditionalAxis = true
            rem = ANY_FASTING_REGEX.replace(rem, " ")
        } else if (NON_FASTING_REGEX.containsMatchIn(rem)) {
            fasting = FastingState.NON_FASTING
            rem = NON_FASTING_REGEX.replace(rem, " ")
        } else if (FASTING_REGEX.containsMatchIn(rem)) {
            fasting = FastingState.FASTING
            rem = FASTING_REGEX.replace(rem, " ")
        }

        // 4. Sub-year ages (months/days/newborn) — never matched positively, excluded for age >= 1
        if (SUB_YEAR_REGEX.containsMatchIn(rem)) {
            subYearOnly = true
            rem = SUB_YEAR_REGEX.replace(rem, " ")
        }

        // 5. Age in years
        AGE_RANGE_REGEX.find(rem)?.let { m ->
            val min = m.groupValues[1].toIntOrNull()
            val max = m.groupValues[2].toIntOrNull()
            if (min != null && max != null && min <= max) {
                ageRange = AgeRange(min, max, minInclusive = true, maxInclusive = true)
                rem = rem.replaceRange(m.range, " ")
            }
        }
        if (ageRange == null) {
            AGE_UPPER_INCLUSIVE_REGEX.find(rem)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { max ->
                    ageRange = AgeRange(null, max, minInclusive = false, maxInclusive = true)
                    rem = rem.replaceRange(m.range, " ")
                }
            }
        }
        if (ageRange == null) {
            AGE_UPPER_STRICT_REGEX.find(rem)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { max ->
                    ageRange = AgeRange(null, max, minInclusive = false, maxInclusive = false)
                    rem = rem.replaceRange(m.range, " ")
                }
            }
        }
        if (ageRange == null) {
            AGE_LOWER_INCLUSIVE_REGEX.find(rem)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { min ->
                    ageRange = AgeRange(min, null, minInclusive = true, maxInclusive = false)
                    rem = rem.replaceRange(m.range, " ")
                }
            }
        }
        if (ageRange == null) {
            AGE_LOWER_STRICT_REGEX.find(rem)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { min ->
                    ageRange = AgeRange(min, null, minInclusive = false, maxInclusive = false)
                    rem = rem.replaceRange(m.range, " ")
                }
            }
        }
        if (ADULT_REGEX.containsMatchIn(rem)) {
            if (ageRange == null) ageRange = AgeRange(18, null, minInclusive = true, maxInclusive = false)
            rem = ADULT_REGEX.replace(rem, " ")
        }
        if (CHILD_REGEX.containsMatchIn(rem)) {
            if (ageRange == null) pediatricUnspecified = true
            rem = CHILD_REGEX.replace(rem, " ")
        }

        // 6. Fail-closed leftover check: any unrecognized word in the label marks an uncaptured qualifier
        val leftoverTokens = WORD_OR_NUM_TOKEN.findAll(rem)
            .map { it.value }
            .filter { it !in SAFE_LABEL_STOPWORDS }
            .toList()
        val hasUncaptured = leftoverTokens.isNotEmpty()

        return StratumCondition(
            sex = sex,
            ageRange = ageRange,
            fasting = fasting,
            pregnancy = pregnancy,
            subYearOnly = subYearOnly,
            pediatricUnspecified = pediatricUnspecified,
            hasUncapturedQualifier = hasUncaptured,
            explicitlyUnconditionalAxis = explicitlyUnconditionalAxis,
        )
    }

    private fun parseExactNumber(raw: String): BigDecimal? {
        val parsed = numberParser.parse(raw)
        if (parsed.needsReview || parsed.comparator != ResultComparator.EQ) return null
        return parsed.value
    }

    private fun normalizeUnit(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('.')?.trimEnd()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed
            .replace('\u03BC', '\u00B5') // Greek mu -> micro sign
            .replace("^2", "²")
            .replace("^3", "³")
            .replace(Regex("\\s+"), " ")
            .replace(" /", "/")
            .replace("/ ", "/")
            .replace(Regex("/([dmµ])l\\b"), "/$1L")
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)

    companion object {
        private const val NUM = """([+-]?[0-9]+(?:[.,][0-9]+)*)"""
        private const val UNIT = """((?:(?:[x×]\s*)?10[\^ˆ]?[+−-]?\d+\s*[/·]\s*)?[%°μµ/\p{L}][%°μµ/\p{L}\p{N}^.*·()-]*(?: [\p{L}][\p{L}\p{N}/.*·^()-]*)?)"""
        private val REF_HEADER_PREFIX = Regex("""^valor(?:es)?\s+de\s+refer[eê]ncia\s*:\s*""", RegexOption.IGNORE_CASE)
        private val PURE_REF_HEADER = Regex("""^valor(?:es)?\s+de\s+referencia\s*:?$""")
        private val RANGE_REGEX = Regex("""^(?:de\s+)?$NUM\s*(?:a|até|ate|–|—|-)\s*$NUM(?:\s*$UNIT)?$""", RegexOption.IGNORE_CASE)
        private val BETWEEN_RANGE_REGEX = Regex("""^entre\s+$NUM\s+e\s+$NUM(?:\s*$UNIT)?$""", RegexOption.IGNORE_CASE)
        private val HALF_OPEN_REGEX = Regex("""^$NUM\s*(<=|≤|<)\s*x\s*(<=|≤|<)\s*$NUM(?:\s*$UNIT)?$""", RegexOption.IGNORE_CASE)
        private val SYMBOLIC_UNILATERAL_REGEX = Regex("""^(<=|>=|<|>|≤|≥)\s*$NUM(?:\s*$UNIT)?$""")
        private val PT_UNILATERAL_REGEX = Regex(
            """^(abaixo\s+de|menor\s+que|inferior\s+a|at[eé]|menor\s+ou\s+igual\s+a|inferior\s+ou\s+igual\s+a|acima\s+de|maior\s+que|superior\s+a|maior\s+ou\s+igual\s+a|superior\s+ou\s+igual\s+a|a\s+partir\s+de)\s+$NUM(?:\s*$UNIT)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val MULTI_OR_CONDITIONAL = Regex(
            """\b(?:ou(?!\s+igual\s+a\b)|adultos?|criancas?|homem|homens|mulher(?:es)?|masculino|feminino|gestantes?|trimestre|risco|desejavel|otimo|limitrofe|elevado|alto|baixo|jejum|pos-prandial|idade|anos|meses|dias|vide|conforme|ver)\b"""
        )

        private val COMMA_LABELED_STRATUM_SPLIT = Regex(""",\s*(?=\p{L}[^,;:]*:)""")
        private val SPACE_LABELED_STRATUM_SPLIT = Regex(
            """(?<!\bsexo)(?<!\be)(?<!\bou)(?<!\bde)(?<!\bnão)(?<!\bnao)(?<!:)\s+(?=(?:homem|homens|mulher(?:es)?|masculino|feminino|sexo\s+(?:masculino|feminino)|adultos?|crian[cç]as?|gestantes?|n[aã]o\s+gestantes?|em\s+jejum|sem\s+jejum)\b[^:]*:)""",
            RegexOption.IGNORE_CASE,
        )
        private val TRAILING_PAREN_CONDITION = Regex("""^(.+?)\s*\(([^()]+)\)\.?$""")
        private val INLINE_BOUND_START = Regex(
            """(?:<=|>=|<|>|≤|≥|abaixo\s+de|menor\s+que|inferior\s+a|at[eé]|menor\s+ou\s+igual\s+a|inferior\s+ou\s+igual\s+a|acima\s+de|maior\s+que|superior\s+a|maior\s+ou\s+igual\s+a|superior\s+ou\s+igual\s+a|a\s+partir\s+de|entre\s+\d|de\s+\d+[.,]?\d*\s*(?:a|at[eé]|–|—|-)|\d+[.,]?\d*\s*(?:a|at[eé]|–|—|-)\s*\d)""",
            RegexOption.IGNORE_CASE,
        )

        private val MALE_REGEX = Regex("""\b(?:sexo\s+masculino|masculino|homem|homens)\b""")
        private val FEMALE_REGEX = Regex("""\b(?:sexo\s+feminino|feminino|mulher(?:es)?)\b""")
        private val NOT_PREGNANT_REGEX = Regex("""\bnao\s+(?:gestantes?|gravidas?)\b""")
        private val PREGNANT_REGEX = Regex("""\b(?:gestantes?|gravidas?)\b""")
        private val ANY_FASTING_REGEX = Regex("""\b(?:com\s+ou\s+sem\s+jejum|sem\s+ou\s+com\s+jejum|jejum\s+opcional)\b""")
        private val NON_FASTING_REGEX = Regex("""\b(?:sem\s+jejum|nao\s+jejum|pos[- ]?prandial)\b""")
        private val FASTING_REGEX = Regex("""\b(?:em\s+jejum|com\s+jejum|jejum)\b""")
        private val SUB_YEAR_REGEX = Regex(
            """\b(?:(?:de\s+|entre\s+)?\d+\s*(?:a|ate|–|—|-|e)\s*\d+\s*(?:meses|mes|dias?|semanas?)|(?:inferior\s+a|menor\s+que|abaixo\s+de|ate|<=|≤|<)\s*\d+\s*(?:meses|mes|dias?|semanas?)|recem[- ]?nascidos?|neonatos?|lactentes?)\b"""
        )
        private val AGE_RANGE_REGEX = Regex("""\b(?:de\s+|entre\s+)?(\d{1,3})\s*(?:a|ate|–|—|-|e)\s*(\d{1,3})\s*anos?\b""")
        private val AGE_UPPER_INCLUSIVE_REGEX = Regex("""(?:ate|menor\s+ou\s+igual\s+a|inferior\s+ou\s+igual\s+a|<=|≤)\s*(\d{1,3})\s*anos?\b""")
        private val AGE_UPPER_STRICT_REGEX = Regex("""(?:abaixo\s+de|menor\s+que|inferior\s+a|<)\s*(\d{1,3})\s*anos?\b""")
        private val AGE_LOWER_INCLUSIVE_REGEX = Regex("""(?:a\s+partir\s+de|maior\s+ou\s+igual\s+a|superior\s+ou\s+igual\s+a|>=|≥)\s*(\d{1,3})\s*anos?\b""")
        private val AGE_LOWER_STRICT_REGEX = Regex("""(?:acima\s+de|maior\s+que|superior\s+a|>)\s*(\d{1,3})\s*anos?\b""")
        private val ADULT_REGEX = Regex("""\badultos?\b""")
        private val CHILD_REGEX = Regex("""\b(?:criancas?|adolescentes?|pediatricos?)\b""")
        private val WORD_OR_NUM_TOKEN = Regex("""[a-z0-9]+""")
        private val SAFE_LABEL_STOPWORDS = setOf(
            "valor", "valores", "referencia", "sexo", "idade", "para", "de", "da", "do", "das", "dos",
            "a", "ao", "aos", "as", "o", "os", "e", "ou", "em", "com", "sem", "ambos", "ambas",
            "geral", "faixa", "etaria", "entre", "partir", "acima", "abaixo", "igual", "anos", "ano",
        )
    }
}
