package br.com.exames.domain.parser

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BiochemistryParserTest {
    private val parser = BiochemistryParser()
    private var index = 0
    private fun span(text: String, x: Double, y: Double, width: Double = 70.0, height: Double = 14.0) =
        SourceFragment("s-${index++}", text, SourceRegion(1, x, y, x + width, y + height, 600.0, 800.0), "PDF_TEXT", "synthetic/1")
    private fun header(layout: String = "simple-v1") = listOf(
        span("Layout: $layout", 25.0, 50.0, 500.0),
        span("Analito Resultado Unidade Referência", 25.0, 130.0, 500.0))
    private fun row(name: String = "Glicose", value: String = "15", reference: String? = "10–20 mg/dL", y: Double = 175.0,
        unit: String = "mg/dL") = listOfNotNull(span(name, 25.0, y), span(value, 175.0, y), span(unit, 270.0, y),
        reference?.let { span(it, 360.0, y, 220.0) })
    private fun parse(spans: List<SourceFragment>) = parser.parse("doc", "run", 1, spans)
    private fun status(value: ParsedObservation) = DeterministicRangeEvaluator().evaluate(value.observation, value.referenceForAssessment).status

    @Test fun recognizesFiveAnalytesAndPreservesOriginalSourceAndStableIds() {
        val names = listOf("Glicose", "Uréia", "Creatinina", "Na+", "K")
        val source = header() + names.flatMapIndexed { i, name -> row(name, y = 175.0 + i * 60) }
        val first = parse(source); val second = parse(source)
        assertEquals(SupportedLayout.SIMPLE, first.layout)
        assertEquals(5, first.observations.size)
        assertEquals(Analyte.entries.toSet(), first.observations.map { Analyte.valueOf(it.observation.analyteNormalized!!) }.toSet())
        first.observations.zip(second.observations).forEach { (a, b) ->
            assertEquals(a.observation.id, b.observation.id)
            assertFalse(a.observation.confirmed)
            assertEquals(AssessmentStatus.WITHIN_RANGE, status(a))
            assertTrue(a.observation.fieldEvidence.all { field -> source.any { it.spanId == field.spanId && it.raw == field.raw && it.region == field.region } })
        }
        assertNull(BiochemistryParser.resolve("Microalbumina"))
        assertNull(BiochemistryParser.resolve("K/Na"))
    }
    @Test fun unsupportedAnalyteAndLayoutAreExplicitRatherThanAssumedSupported() {
        val unknownAnalyte = parse(header() + row("Colesterol"))
        assertTrue(ParseWarning.UNSUPPORTED_ANALYTE in unknownAnalyte.warnings)
        assertTrue(unknownAnalyte.unsupported.isNotEmpty())
        val unknownLayout = parse(header("different-v9") + row())
        assertNull(unknownLayout.layout)
        assertTrue(ParseWarning.UNSUPPORTED_LAYOUT in unknownLayout.warnings)
        assertFalse(unknownLayout.unsupported.isEmpty())
    }
    @Test fun footnoteMustMatchAnExplicitUniqueLink() {
        val fields = header("footnote-v1") + row(reference = "Ver nota 1") +
            listOf(span("1.", 25.0, 565.0, 15.0), span("10–20 mg/dL", 65.0, 565.0, 200.0))
        val parsed = parse(fields).observations.single()
        assertEquals(AssessmentStatus.WITHIN_RANGE, status(parsed))
        assertTrue(parsed.observation.fieldEvidence.any { it.field == "referenceRaw" && it.region.top == 565.0 })
        val missing = parse(header("footnote-v1") + row(reference = "Ver nota 1")).observations.single()
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(missing))
    }
    @Test fun duplicateFootnoteTargetsAreBothPreservedAndNeverRankedByValue() {
        val input = header("footnote-v1") + row(reference = "Ver nota 1") +
            listOf(span("1.", 25.0, 565.0, 15.0), span("10–20 mg/dL", 65.0, 565.0, 200.0),
                span("1.", 25.0, 605.0, 15.0), span("30–40 mg/dL", 65.0, 605.0, 200.0))
        val output = parse(input).observations.single()
        assertEquals(2, output.observation.referenceCandidates.size)
        assertNull(output.referenceForAssessment)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(output))
    }
    @Test fun competingRangesCannotBeSelectedBecauseOneContainsTheResult() {
        val parsed = parse(header() + row(reference = "10–20 mg/dL ou 30–40 mg/dL")).observations.single()
        assertEquals(2, parsed.observation.referenceCandidates.size)
        assertEquals("10–20 mg/dL ou 30–40 mg/dL", parsed.observation.referenceRaw)
        assertNull(parsed.referenceForAssessment)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(parsed))
    }
    @Test fun conditionalReferencesRequireLiteralDocumentContext() {
        val reference = "Adultos: 10–20 mg/dL; Crianças: 30–40 mg/dL"
        val missing = parse(header() + row(reference = reference)).observations.single()
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(missing))
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(missing.copy(observation = missing.observation.copy(confirmed = true))))
        val explicit = parse(header() + span("Contexto: Adultos", 25.0, 80.0, 200.0) + row(reference = reference)).observations.single()
        assertEquals(AssessmentStatus.WITHIN_RANGE, status(explicit))
        assertEquals(2, explicit.observation.referenceCandidates.size)
        assertEquals(Applicability.APPLICABLE, explicit.referenceForAssessment!!.applicability)
    }
    @Test fun doesNotInferPatientGroupFromAgeOrChooseAmongConflictingContexts() {
        val reference = "Adultos: 10–20 mg/dL; Crianças: 30–40 mg/dL"
        val age = parse(header() + span("Idade: 40 anos", 25.0, 80.0, 200.0) + row(reference = reference)).observations.single()
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(age))
        val conflict = parse(header() + listOf(span("Contexto: Adultos", 25.0, 70.0, 200.0),
            span("Contexto: Crianças", 25.0, 100.0, 200.0)) + row(reference = reference)).observations.single()
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(conflict))
    }
    @Test fun ambiguousNumbersCensoringAndUnitsRemainConservative() {
        for (value in listOf("1,234", "1.234", "< 0,1")) {
            val result = parse(header() + row(value = value)).observations.single()
            assertEquals(AssessmentStatus.NEEDS_REVIEW, status(result))
        }
        val mismatch = parse(header() + row(reference = "10–20 mmol/L")).observations.single()
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(mismatch))
    }
    @Test fun missingAndQualitativeAreNotPresentedAsWithin() {
        assertEquals(AssessmentStatus.NOT_CLASSIFIABLE, status(parse(header() + row(reference = null)).observations.single()))
        assertEquals(AssessmentStatus.NOT_CLASSIFIABLE, status(parse(header() + row(value = "Não reagente")).observations.single()))
    }
    @Test fun respectsHalfOpenUnilateralAndInvalidRangesThroughSameEvaluator() {
        val exclusive = parse(header() + row(value = "20", reference = "10 ≤ x < 20 mg/dL")).observations.single()
        assertEquals(AssessmentStatus.ABOVE_RANGE, status(exclusive))
        assertEquals(AssessmentStatus.ABOVE_RANGE, status(parse(header() + row(value = "20", reference = "< 20 mg/dL")).observations.single()))
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(parse(header() + row(reference = "20–10 mg/dL")).observations.single()))
    }
    @Test fun duplicatesAndOverlappingDenseRowsRequireReview() {
        val duplicates = parse(header() + row() + row(y = 220.0))
        assertEquals(2, duplicates.observations.size)
        assertTrue(duplicates.observations.all { status(it) == AssessmentStatus.NEEDS_REVIEW })
        assertTrue(ParseWarning.DUPLICATE_ANALYTE in duplicates.warnings)
        val shared = header() + listOf(span("Glicose", 25.0, 175.0), span("Ureia", 25.0, 185.0),
            span("15", 175.0, 178.0, height = 20.0), span("mg/dL", 270.0, 178.0, height = 20.0),
            span("10–20 mg/dL", 360.0, 178.0, 220.0, 20.0))
        assertTrue(parse(shared).observations.all { status(it) == AssessmentStatus.NEEDS_REVIEW })
    }
    @Test fun rejectsMixedViewportsAndDuplicateSourceIds() {
        val source = header() + row()
        assertThrows(IllegalArgumentException::class.java) { parse(source + source.last()) }
        val changed = source.last().copy(region = SourceRegion(1, 360.0, 175.0, 580.0, 189.0, 1200.0, 1600.0))
        assertThrows(IllegalArgumentException::class.java) { parse(source.dropLast(1) + changed) }
    }
    @Test fun recognizesOnlyDeclaredWholeAbbreviations() {
        val aliases = mapOf("GLI" to Analyte.GLUCOSE, "URE" to Analyte.UREA, "CREA" to Analyte.CREATININE,
            "Na" to Analyte.SODIUM, "K+" to Analyte.POTASSIUM)
        aliases.forEach { (raw, expected) -> assertEquals(expected, BiochemistryParser.resolve(raw)) }
        assertNull(BiochemistryParser.resolve("GLIadina"))
        assertNull(BiochemistryParser.resolve("CREA estimada"))
    }
    @Test fun unknownGeometryCannotSilentlyRemovePartOfAnObservation() {
        val source = header() + row() + SourceFragment("unknown", "5", null, "OCR", "synthetic/1")
        val output = parse(source)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(output.observations.single()))
        assertEquals(source.last(), output.unsupported.single())
    }
    @Test fun glyphInkGapsCannotInventSeparatorsInsideSourceNumbers() {
        val digits = listOf(span("1", 175.0, 175.0, 3.0, 8.0).copy(spaceBefore = true),
            span("5", 182.0, 175.0, 5.0, 8.0).copy(spaceBefore = false))
        val output = parse(header() + row().filterNot { it.raw == "15" } + digits).observations.single()
        assertEquals("15", output.observation.resultRaw)
        assertEquals(AssessmentStatus.WITHIN_RANGE, status(output))
        val separated = parse(header() + row().filterNot { it.raw == "15" } +
            digits.map { it.copy(spaceBefore = true) }).observations.single()
        assertEquals("1 5", separated.observation.resultRaw)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, status(separated))
    }
}
