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

        val noSpaceUnit = assessor.assess(obs("4,2", "mg/L", "Menor que 5,0mg/L"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, noSpaceUnit.status)

        val percentNoSpace = assessor.assess(obs("55", "%", "40 a 70%"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, percentNoSpace.status)

        val dePrefix = assessor.assess(obs("0,95", "mg/dL", "De 0,70 a 1,20 mg/dL."))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, dePrefix.status)

        val entrePrefix = assessor.assess(obs("85", "mg/dL", "Entre 70 e 99 mg/dL"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, entrePrefix.status)

        val inferiorOuIgual = assessor.assess(obs("10,0", "mg/L", "Inferior ou igual a 10,0 mg/L"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, inferiorOuIgual.status)

        val menorOuIgual = assessor.assess(obs("10,0", "mg/L", "Menor ou igual a 10,0 mg/L"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, menorOuIgual.status)

        val superiorOuIgual = assessor.assess(obs("60", "mL/min", "Superior ou igual a 60 mL/min"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, superiorOuIgual.status)

        val maiorOuIgual = assessor.assess(obs("60", "mL/min", "Maior ou igual a 60 mL/min"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, maiorOuIgual.status)

        val refHeaderPrefix = assessor.assess(obs("85", "mg/dL", "Valor de referência: 70 a 99 mg/dL"))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, refHeaderPrefix.status)
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
            "Valor de referência: Homens: 13,5 a 17,5 g/dL",
            "De 18 a 60 anos: 70 a 99 mg/dL",
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
        assertEquals(DocumentaryStatus.WITHIN_RANGE, refWithoutUnit.status)
        assertEquals(DocumentaryReason.WITHIN_DOCUMENT_RANGE, refWithoutUnit.reason)
        assertEquals(true, refWithoutUnit.referenceUnitImplicit)

        val refWithoutUnitAbove = assessor.assess(obs("110", "mg/dL", "70 a 99"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, refWithoutUnitAbove.status)
        assertEquals(DocumentaryReason.ABOVE_DOCUMENT_RANGE, refWithoutUnitAbove.reason)
        assertEquals(true, refWithoutUnitAbove.referenceUnitImplicit)

        val obsWithoutUnit = assessor.assess(obs("85", null, "70 a 99 mg/dL"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, obsWithoutUnit.status)
        assertEquals(DocumentaryReason.UNIT_MISMATCH_OR_MISSING, obsWithoutUnit.reason)

        val bothWithoutUnit = assessor.assess(obs("85", null, "70 a 99"))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, bothWithoutUnit.status)
        assertEquals(DocumentaryReason.UNIT_MISMATCH_OR_MISSING, bothWithoutUnit.reason)

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
        val assessed = assessor.assess(scanned, ClinicalContext(sex = BiologicalSex.MALE, ageYears = 35))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, assessed.status)
        assertEquals(DocumentaryReason.VISUAL_EXTRACTION_WITHOUT_DIGITAL_TEXT, assessed.reason)
        assertEquals(1, region.page)
    }

    @Test fun resolvesStratifiedReferencesDeterministicallyOnlyWhenEphemeralContextUniquelyMatches() {
        val sexRef = "Homem: 13,5 a 17,5 g/dL; Mulher: 12,0 a 15,5 g/dL"
        // Without context -> NEEDS_REVIEW
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, assessor.assess(obs("14,2", "g/dL", sexRef)).status)
        // With matching sex -> WITHIN_RANGE and preserves applied excerpt
        val maleWithin = assessor.assess(obs("14,2", "g/dL", sexRef), ClinicalContext(sex = BiologicalSex.MALE))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, maleWithin.status)
        assertEquals("Homem: 13,5 a 17,5 g/dL", maleWithin.appliedReferenceExcerpt)
        // Out of range for female -> NEEDS_REVIEW (ABOVE_DOCUMENT_RANGE)
        val femaleAbove = assessor.assess(obs("16,2", "g/dL", sexRef), ClinicalContext(sex = BiologicalSex.FEMALE))
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, femaleAbove.status)
        assertEquals(DocumentaryReason.ABOVE_DOCUMENT_RANGE, femaleAbove.reason)

        // Sex + Age stratified table (including hierarchical sex headers and pediatric sub-year exclusion)
        val ageSexRef = """
            Sexo masculino: Inferior a 12 meses: 5,0 a 10,0 U/L
            De 1 a 17 anos: 8,0 a 20,0 U/L
            De 18 a 49 anos: 10,0 a 50,0 U/L
            Acima de 49 anos: 15,0 a 60,0 U/L
            Sexo feminino: De 18 a 49 anos: 8,0 a 40,0 U/L
            Acima de 49 anos: 10,0 a 45,0 U/L
        """.trimIndent()
        // Only sex without age -> still NEEDS_REVIEW
        assertEquals(
            DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE,
            assessor.assess(obs("32,0", "U/L", ageSexRef), ClinicalContext(sex = BiologicalSex.MALE)).reason,
        )
        // Sex + age 35 -> uniquely matches 18..49 male stratum
        val male35 = assessor.assess(
            obs("32,0", "U/L", ageSexRef),
            ClinicalContext(sex = BiologicalSex.MALE, ageYears = 35),
        )
        assertEquals(DocumentaryStatus.WITHIN_RANGE, male35.status)
        assertEquals("De 18 a 49 anos: 10,0 a 50,0 U/L", male35.appliedReferenceExcerpt)

        // Fasting stratified reference
        val fastingRef = "Em jejum: 70 a 99 mg/dL; Sem jejum: até 140 mg/dL"
        val fastingOk = assessor.assess(
            obs("88", "mg/dL", fastingRef),
            ClinicalContext(fasting = FastingState.FASTING),
        )
        assertEquals(DocumentaryStatus.WITHIN_RANGE, fastingOk.status)
        val nonFastingAbove = assessor.assess(
            obs("145", "mg/dL", fastingRef),
            ClinicalContext(fasting = FastingState.NON_FASTING),
        )
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, nonFastingAbove.status)
        assertEquals(DocumentaryReason.ABOVE_DOCUMENT_RANGE, nonFastingAbove.reason)

        // Pregnancy stratified reference
        val pregRef = "Mulheres (não gestantes): 12,0 a 15,5 g/dL; Gestantes: 11,0 a 14,0 g/dL"
        val notPreg = assessor.assess(
            obs("13,0", "g/dL", pregRef),
            ClinicalContext(sex = BiologicalSex.FEMALE, pregnancy = PregnancyState.NOT_PREGNANT),
        )
        assertEquals(DocumentaryStatus.WITHIN_RANGE, notPreg.status)
        // Female without pregnancy specified -> stays NEEDS_REVIEW
        assertEquals(
            DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE,
            assessor.assess(obs("13,0", "g/dL", pregRef), ClinicalContext(sex = BiologicalSex.FEMALE)).reason,
        )

        // Parenthetical condition after bound (e.g., lipid/cholesterol adult vs pediatric)
        val parenRef = "Inferior a 190 mg/dL (Adultos acima de 20 anos); Inferior a 170 mg/dL (Crianças e adolescentes)"
        val adultParen = assessor.assess(obs("175", "mg/dL", parenRef), ClinicalContext(ageYears = 35))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, adultParen.status)
        assertEquals("Inferior a 190 mg/dL (Adultos acima de 20 anos)", adultParen.appliedReferenceExcerpt)
        // Without age or for minor -> stays NEEDS_REVIEW
        assertEquals(
            DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE,
            assessor.assess(obs("175", "mg/dL", parenRef), ClinicalContext(ageYears = 15)).reason,
        )
    }

    @Test fun keepsFailClosedNeedsReviewForUncapturedQualifiersOverlapsAndMissingUnitsEvenWithContext() {
        val fullContext = ClinicalContext(
            sex = BiologicalSex.FEMALE,
            ageYears = 35,
            fasting = FastingState.FASTING,
            pregnancy = PregnancyState.NOT_PREGNANT,
        )
        // Clinical risk tiers are never captured -> MUST stay NEEDS_REVIEW
        val riskRef = "Desejável: < 130 mg/dL\nRisco alto: < 70 mg/dL"
        assertEquals(
            DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE,
            assessor.assess(obs("80", "mg/dL", riskRef), fullContext).reason,
        )
        // Menstrual cycle phases are not captured -> stays NEEDS_REVIEW for FEMALE
        val cycleRef = "Mulher: Fase folicular: 3,5 a 12,5 mUI/mL; Fase lútea: 1,7 a 7,7 mUI/mL; Homem: 1,5 a 12,4 mUI/mL"
        assertEquals(
            DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE,
            assessor.assess(obs("5,0", "mUI/mL", cycleRef), fullContext).reason,
        )
        // Same table for MALE excludes female cycle strata and uniquely matches Homem
        val maleCycle = assessor.assess(
            obs("5,0", "mUI/mL", cycleRef),
            ClinicalContext(sex = BiologicalSex.MALE, ageYears = 35),
        )
        assertEquals(DocumentaryStatus.WITHIN_RANGE, maleCycle.status)

        // Missing unit on matched stratified reference uses observation unit when observation has an explicit unit
        val noUnitStratified = "Homem: 13,5 a 17,5; Mulher: 12,0 a 15,5"
        val resolvedNoUnitStratified = assessor.assess(obs("14,0", "g/dL", noUnitStratified), ClinicalContext(sex = BiologicalSex.MALE))
        assertEquals(DocumentaryStatus.WITHIN_RANGE, resolvedNoUnitStratified.status)
        assertEquals(true, resolvedNoUnitStratified.referenceUnitImplicit)

        // Explicit unit mismatch on matched stratified reference MUST still fail with UNIT_MISMATCH_OR_MISSING
        val mismatchStratified = "Homem: 13,5 a 17,5 mmol/L; Mulher: 12,0 a 15,5 mmol/L"
        assertEquals(
            DocumentaryReason.UNIT_MISMATCH_OR_MISSING,
            assessor.assess(obs("14,0", "g/dL", mismatchStratified), ClinicalContext(sex = BiologicalSex.MALE)).reason,
        )

        // Overlapping age strata (ambiguous at boundary 30) -> stays NEEDS_REVIEW
        val overlapping = "De 18 a 30 anos: 10 a 20 U/L; De 30 a 50 anos: 15 a 30 U/L"
        assertEquals(
            DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE,
            assessor.assess(obs("18", "U/L", overlapping), ClinicalContext(ageYears = 30)).reason,
        )
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
