package br.com.exames.domain.extraction

import br.com.exames.domain.validation.SourceRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConservativeExamAssessorTest {
    private val assessor = ConservativeExamAssessor()

    @Test fun classifiesWithinInclusiveAndUnilateralBoundsWithMatchingUnit() {
        val inside = assessor.assess(obs("14,2", "g/dL", "13,5 a 17,5 g/dL"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, inside.status)
        assertEquals(DocumentaryReason.WITHIN_DOCUMENT_RANGE, inside.reason)

        val onLow = assessor.assess(obs("70", "mg/dL", "70 a 99 mg/dL"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, onLow.status)

        val onHigh = assessor.assess(obs("99", "mg/dL", "70 a 99 mg/dL"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, onHigh.status)

        val belowUpper = assessor.assess(obs("129,9", "mg/dL", "< 130 mg/dL"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, belowUpper.status)

        val ptBelow = assessor.assess(obs("120", "mg/dL", "abaixo de 130 mg/dL"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, ptBelow.status)

        val ptUpTo = assessor.assess(obs("130", "mg/dL", "até 130 mg/dL"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, ptUpTo.status)

        val aboveLower = assessor.assess(obs("65", "mL/min", ">= 60 mL/min"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, aboveLower.status)

        val microNormalized = assessor.assess(obs("4,5", "10^6/\u03BCL", "4,0 a 5,5 10^6/\u00B5L"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, microNormalized.status)
    }

    @Test fun classifiesAboveAndBelowBoundsAsNeedsReview() {
        val above = assessor.assess(obs("118", "mg/dL", "70 a 99 mg/dL"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, above.status)
        assertEquals(DocumentaryReason.ABOVE_DOCUMENT_RANGE, above.reason)

        val below = assessor.assess(obs("69,9", "mg/dL", "70 a 99 mg/dL"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, below.status)
        assertEquals(DocumentaryReason.BELOW_DOCUMENT_RANGE, below.reason)

        val equalStrictUpper = assessor.assess(obs("130", "mg/dL", "< 130 mg/dL"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, equalStrictUpper.status)
        assertEquals(DocumentaryReason.ABOVE_DOCUMENT_RANGE, equalStrictUpper.reason)

        val equalStrictLower = assessor.assess(obs("60", "mL/min", "> 60 mL/min"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, equalStrictLower.status)
        assertEquals(DocumentaryReason.BELOW_DOCUMENT_RANGE, equalStrictLower.reason)
    }

    @Test fun enforcesStrictNeedsReviewForAmbiguousMultiCriteriaAndConditionalReferences() {
        val uncertain = assessor.assess(obs("14,2", "g/dL", "13,5 a 17,5 g/dL", uncertain = true))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, uncertain.status)
        assertEquals(DocumentaryReason.UNCERTAIN_REFERENCE_LINK, uncertain.reason)

        for (ref in listOf(
            "Desejável: < 130 mg/dL\nRisco alto: < 70 mg/dL",
            "Homens: 13,5 a 17,5 g/dL; Mulheres: 12,0 a 15,5 g/dL",
            "Adultos: < 8 mg/L",
            "70 a 99 mg/dL ou < 100 mg/dL",
            "Desejável < 130 mg/dL",
        )) {
            val assessed = assessor.assess(obs("80", "mg/dL", ref))
            assertEquals(DocumentaryStatus.NEEDS_REVIEW, assessed.status, "Expected NEEDS_REVIEW for: $ref")
            assertEquals(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE, assessed.reason, "Unexpected reason for: $ref")
        }
    }

    @Test fun enforcesStrictNeedsReviewForUnitMismatchMissingUnitAndCensoredValues() {
        val mismatch = assessor.assess(obs("5,0", "mmol/L", "70 a 99 mg/dL"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, mismatch.status)
        assertEquals(DocumentaryReason.UNIT_MISMATCH_OR_MISSING, mismatch.reason)

        val refWithoutUnit = assessor.assess(obs("85", "mg/dL", "70 a 99"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, refWithoutUnit.status)
        assertEquals(DocumentaryReason.UNIT_MISMATCH_OR_MISSING, refWithoutUnit.reason)

        val obsWithoutUnit = assessor.assess(obs("85", null, "70 a 99 mg/dL"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, obsWithoutUnit.status)
        assertEquals(DocumentaryReason.UNIT_MISMATCH_OR_MISSING, obsWithoutUnit.reason)

        val censored = assessor.assess(obs("< 7,5", "mg/L", "< 8 mg/L"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, censored.status)
        assertEquals(DocumentaryReason.CENSORED_OR_AMBIGUOUS_VALUE, censored.reason)

        val ambiguousThousand = assessor.assess(obs("1.234", "/µL", "1000 a 5000 /µL"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, ambiguousThousand.status)
        assertEquals(DocumentaryReason.CENSORED_OR_AMBIGUOUS_VALUE, ambiguousThousand.reason)

        val invertedRange = assessor.assess(obs("85", "mg/dL", "100 a 70 mg/dL"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, invertedRange.status)
        assertEquals(DocumentaryReason.INVALID_REFERENCE_SYNTAX, invertedRange.reason)
    }

    @Test fun classifiesMissingReferenceAndQualitativeResultsAsNotClassifiable() {
        val noRef = assessor.assess(obs("14,2", "g/dL", null))
        assertEquals(DocumentaryStatus.NOT_CLASSIFIABLE, noRef.status)
        assertEquals(DocumentaryReason.NO_REFERENCE, noRef.reason)

        val qualitative = assessor.assess(obs("Não reagente", null, "Não reagente"))
        assertEquals(DocumentaryStatus.NOT_CLASSIFIABLE, qualitative.status)
        assertEquals(DocumentaryReason.NON_NUMERIC_RESULT, qualitative.reason)

        val uncertainNoRef = assessor.assess(obs("14,2", "g/dL", null, uncertain = true))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, uncertainNoRef.status)
        assertEquals(DocumentaryReason.UNCERTAIN_REFERENCE_LINK, uncertainNoRef.reason)
    }

    @Test fun forcesNeedsReviewForVisualOnlyScannedObservationsEvenWhenNumericInRange() {
        val region = SourceRegion(1, 10.0, 20.0, 80.0, 30.0, 500.0, 800.0)
        val scanned = ExamObservation(
            name = ExamCitation("p1vis1n", "Glicose", emptyList()),
            sourceResult = ExamCitation("p1vis1v", "82 mg/dL", emptyList()),
            result = "82",
            unit = "mg/dL",
            reference = ExamCitation("p1vis1f", "70 a 99 mg/dL", emptyList()),
            referenceNeedsReview = false,
            unitCitation = null,
            visualOnly = true,
            page = 1,
        )
        val assessed = assessor.assess(scanned)
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, assessed.status)
        assertEquals(DocumentaryReason.VISUAL_EXTRACTION_WITHOUT_DIGITAL_TEXT, assessed.reason)
        assertEquals(1, region.page)
    }

    private fun obs(value: String, unit: String?, reference: String?, uncertain: Boolean = false): ExamObservation {
        val region = SourceRegion(1, 10.0, 20.0, 80.0, 30.0, 500.0, 800.0)
        return ExamObservation(
            name = ExamCitation("n1", "Exame X", listOf(region)),
            sourceResult = ExamCitation("v1", listOfNotNull(value, unit).joinToString(" "), listOf(region)),
            result = value,
            unit = unit,
            reference = reference?.let { ExamCitation("f1", it, listOf(region)) },
            referenceNeedsReview = uncertain,
        )
    }
}
