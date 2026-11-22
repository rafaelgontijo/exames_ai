package br.com.exames.domain.evaluator

import br.com.exames.domain.model.*
import br.com.exames.domain.model.AssessmentStatus.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Synthetic glucose-like values are fixtures, never clinical reference recommendations. */
class DeterministicRangeEvaluatorTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val evaluator = DeterministicRangeEvaluator(Clock.fixed(now, ZoneOffset.UTC))
    private fun observation(value: String = "15") = Observation(
        id = "synthetic-observation", documentId = "synthetic-document", analyteRaw = "Analito sintético",
        resultRaw = value, unitRaw = "u/L", page = 1, sourceSnippet = "Fonte sintética",
        numericValue = value.toBigDecimal(), confirmed = true,
    )
    private fun reference() = ReferenceRange(
        observationId = "synthetic-observation", referenceRaw = "10–20 u/L", unit = "u/L", sourcePage = 1,
        low = "10".toBigDecimal(), high = "20".toBigDecimal(), applicability = Applicability.APPLICABLE,
    )
    @ParameterizedTest
    @CsvSource("9.99,BELOW_RANGE", "10,WITHIN_RANGE", "15,WITHIN_RANGE", "20.00,WITHIN_RANGE", "20.01,ABOVE_RANGE")
    fun inclusiveInterval(value: String, status: AssessmentStatus) {
        val result = evaluator.evaluate(observation(value), reference())
        assertEquals(status, result.status)
        assertEquals(now, result.evaluatedAt)
        assertEquals("REF_NUMERIC/2.0.0", result.ruleVersion)
    }
    @ParameterizedTest
    @CsvSource("10,false,true,BELOW_RANGE", "20,true,false,ABOVE_RANGE", "10,true,false,WITHIN_RANGE", "20,false,true,WITHIN_RANGE")
    fun intervalInclusivity(value: String, lowInclusive: Boolean, highInclusive: Boolean, status: AssessmentStatus) {
        assertEquals(status, evaluator.evaluate(observation(value), reference().copy(
            lowInclusive = lowInclusive, highInclusive = highInclusive)).status)
    }
    @ParameterizedTest
    @CsvSource("LT,20,ABOVE_RANGE", "LT,19,WITHIN_RANGE", "LTE,20,WITHIN_RANGE", "LTE,21,ABOVE_RANGE",
        "GT,10,BELOW_RANGE", "GT,11,WITHIN_RANGE", "GTE,10,WITHIN_RANGE", "GTE,9,BELOW_RANGE")
    fun oneSidedComparators(op: ReferenceComparator, value: String, status: AssessmentStatus) {
        val upper = op == ReferenceComparator.LT || op == ReferenceComparator.LTE
        val r = reference().copy(comparator = op,
            low = if (upper) null else "10".toBigDecimal(), high = if (upper) "20".toBigDecimal() else null)
        assertEquals(status, evaluator.evaluate(observation(value), r).status)
    }
    @ParameterizedTest @EnumSource(Ambiguity::class)
    fun everyAmbiguityRequiresReview(ambiguity: Ambiguity) {
        val result = evaluator.evaluate(observation().copy(ambiguities = setOf(ambiguity)), reference())
        assertEquals(NEEDS_REVIEW, result.status)
        assertTrue(result.requiresReview)
    }
    @ParameterizedTest @EnumSource(value = ResultComparator::class, names = ["EQ"], mode = EnumSource.Mode.EXCLUDE)
    fun censoredOrUnknownResultsRequireReview(comparator: ResultComparator) {
        assertEquals(NEEDS_REVIEW, evaluator.evaluate(observation().copy(resultComparator = comparator), reference()).status)
    }
    @ParameterizedTest @EnumSource(value = Applicability::class, names = ["APPLICABLE"], mode = EnumSource.Mode.EXCLUDE)
    fun clinicalContextCannotBeAssumed(applicability: Applicability) {
        assertEquals(NEEDS_REVIEW, evaluator.evaluate(observation(), reference().copy(applicability = applicability)).status)
    }
    @Test fun absenceOfReferenceNeverInventsOne() {
        assertEquals(NOT_CLASSIFIABLE, evaluator.evaluate(observation(), null).status)
    }
    @Test fun unconfirmedDataIsEvaluatedWithoutFabricatingReview() {
        val original = observation().copy(confirmed = false)
        val result = evaluator.evaluate(original, reference())
        assertEquals(WITHIN_RANGE, result.status)
        assertEquals(ReasonCode.INSIDE_REFERENCE, result.reasonCode)
        assertFalse(original.confirmed)
    }
    @Test fun confirmationCannotResolveAnyClinicalAmbiguity() {
        for (confirmed in listOf(false, true)) {
            for (ambiguity in Ambiguity.entries) {
                val original = observation().copy(confirmed = confirmed, ambiguities = setOf(ambiguity))
                assertEquals(NEEDS_REVIEW, evaluator.evaluate(original, reference()).status)
                assertEquals(confirmed, original.confirmed)
            }
            assertEquals(NEEDS_REVIEW, evaluator.evaluate(observation().copy(confirmed = confirmed),
                reference().copy(applicability = Applicability.UNKNOWN)).status)
        }
    }
    @Test fun referenceMustBelongToObservation() {
        assertEquals(NEEDS_REVIEW, evaluator.evaluate(observation(), reference().copy(observationId = "other")).status)
    }
    @Test fun unitsCannotBeGuessedOrConverted() {
        listOf(null, "", "mg/dL", "U/L").forEach { unit ->
            assertEquals(NEEDS_REVIEW, evaluator.evaluate(observation().copy(unitRaw = unit, unitNormalized = "u/L"), reference()).status)
        }
        assertEquals(NEEDS_REVIEW, evaluator.evaluate(observation(), reference().copy(unit = null)).status)
    }
    @Test fun qualitativeResultIsNotNumeric() {
        assertEquals(NOT_CLASSIFIABLE, evaluator.evaluate(observation().copy(resultRaw = "Negativo", numericValue = null), reference()).status)
    }
    @Test fun malformedReferenceRequiresReview() {
        val r = reference()
        listOf(r.copy(low = null), r.copy(high = null), r.copy(low = "21".toBigDecimal()),
            r.copy(low = r.high, lowInclusive = false), r.copy(comparator = ReferenceComparator.LT),
            r.copy(comparator = ReferenceComparator.UNKNOWN)).forEach {
            assertEquals(NEEDS_REVIEW, evaluator.evaluate(observation(), it).status)
        }
    }
    @Test fun equalClosedBoundsAcceptOnlyExactValue() {
        val r = reference().copy(low = "20".toBigDecimal())
        assertEquals(WITHIN_RANGE, evaluator.evaluate(observation("20"), r).status)
        assertEquals(BELOW_RANGE, evaluator.evaluate(observation("19.99"), r).status)
    }
    @ParameterizedTest @ValueSource(strings = ["< 15", "1,234", "inválido", "16"])
    fun rawResultMustAgreeWithStructuredScalar(raw: String) {
        assertEquals(NEEDS_REVIEW, evaluator.evaluate(observation().copy(resultRaw = raw), reference()).status)
    }
    @Test fun rawFieldsArePreserved() {
        val o = observation().copy(resultRaw = "15,0", analyteRaw = " Original ")
        val before = o.copy()
        evaluator.evaluate(o, reference())
        assertEquals(before, o)
    }
}
