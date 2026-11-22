package br.com.exames.domain.inference

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.AssessmentStatus
import br.com.exames.domain.parser.*
import br.com.exames.domain.validation.SourceRegion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProposalEvidenceValidatorTest {
    private fun span(id: String, raw: String, x: Double, y: Double, width: Double = 70.0) =
        SourceFragment(id, raw, SourceRegion(1, x, y, x + width, y + 14, 600.0, 800.0), "PDF_TEXT", "test/1")
    private fun source(reference: String = "10 a 20 mg/dL") = listOf(
        span("layout", "Layout: simple-v1", 25.0, 50.0, 500.0),
        span("header", "Analito Resultado Unidade Referência", 25.0, 130.0, 500.0),
        span("analyte", "Glicose", 25.0, 175.0), span("result", "15", 175.0, 175.0),
        span("unit", "mg/dL", 270.0, 175.0), span("reference", reference, 360.0, 175.0, 220.0))
    private val field = ProposedField("Glicose", listOf("e3"))
    private val original = ProposedObservation(field, ProposedField("15", listOf("e4")),
        ProposedField("mg/dL", listOf("e5")), listOf(ProposedField("10 a 20 mg/dL", listOf("e6"))), null)
    private fun validate(candidate: ProposedObservation = original, input: List<SourceFragment> = source(), extra: Boolean = false): EvidenceValidation {
        val baseline = BiochemistryParser().parse("doc", "run", 1, input)
        return ProposalEvidenceValidator().validate(ProposalProtocol.compile(1, input),
            FieldProposal(if (extra) listOf(candidate, candidate) else listOf(candidate)), baseline)
    }
    private fun reviewed(value: EvidenceValidation) = assertTrue(value.observations.all {
        DeterministicRangeEvaluator().evaluate(it.observation, it.referenceForAssessment).status == AssessmentStatus.NEEDS_REVIEW
    })
    @Test fun exactSupportedEvidenceCorroboratesWithoutHumanConfirmation() {
        val output = validate()
        assertTrue(output.issues.isEmpty()); assertNotNull(output.acceptedProposal)
        assertFalse(output.observations.single().observation.confirmed)
        assertEquals(AssessmentStatus.WITHIN_RANGE, DeterministicRangeEvaluator().evaluate(
            output.observations.single().observation, output.observations.single().referenceForAssessment).status)
    }
    @Test fun rejectsInventedRangeUnknownCitationAndUnsupportedAnalyte() {
        for (candidate in listOf(
            original.copy(references = listOf(ProposedField("50 a 90 mg/dL", listOf("e6")))),
            original.copy(result = ProposedField("15", listOf("e999"))),
            original.copy(analyte = ProposedField("Colesterol", listOf("e3"))))) {
            val output = validate(candidate); assertNull(output.acceptedProposal); reviewed(output)
        }
    }
    @Test fun literalValueOnWrongRowCannotAuthorizeAssociation() {
        val input = source() + listOf(span("other-name", "Ureia", 25.0, 220.0), span("other-value", "15", 175.0, 220.0),
            span("other-unit", "mg/dL", 270.0, 220.0), span("other-range", "10 a 20 mg/dL", 360.0, 220.0, 220.0))
        val output = validate(original.copy(result = ProposedField("15", listOf("e8"))), input)
        assertTrue(ModelEvidenceIssue.WRONG_ASSOCIATION in output.issues); reviewed(output)
    }
    @Test fun preservesPreviousAmbiguityEvenWhenModelAgrees() {
        val input = source("Adultos: 10 a 20 mg/dL")
        val output = validate(original.copy(references = listOf(ProposedField("Adultos: 10 a 20 mg/dL", listOf("e6")))), input)
        assertTrue(output.issues.isEmpty()); reviewed(output)
    }
    @Test fun rejectsDroppedCompetingRangesAndDuplicateObservations() {
        val competing = validate(input = source("10 a 20 mg/dL ou 30 a 40 mg/dL"))
        assertTrue(ModelEvidenceIssue.DIVERGENCE in competing.issues); reviewed(competing)
        val duplicated = validate(extra = true)
        assertTrue(ModelEvidenceIssue.DUPLICATE_OBSERVATION in duplicated.issues); reviewed(duplicated)
    }
    @Test fun unknownGeometryAndInventedContextDoNotClearReview() {
        val input = source().map { if (it.spanId == "reference") it.copy(region = null) else it }
        reviewed(validate(input = input))
        val context = validate(original.copy(context = ProposedField("Adultos", listOf("e1"))))
        assertNotNull(context.issues.firstOrNull()); reviewed(context)
    }
    @Test fun citingCorrectTextForWrongFieldIsRejected() {
        val output = validate(original.copy(unit = ProposedField("Glicose", listOf("e3"))))
        assertTrue(ModelEvidenceIssue.WRONG_ASSOCIATION in output.issues); reviewed(output)
    }
    @Test fun contextMustPreserveTheWholeExplicitSourceRatherThanAChosenSubstring() {
        val context = span("context", "Contexto: Adultos", 25.0, 80.0, 220.0)
        val output = validate(original.copy(context = ProposedField(context.raw, listOf("e7"))), source() + context)
        assertTrue(output.issues.isEmpty())
        val wrong = validate(original.copy(context = ProposedField("Adultos", listOf("e7"))), source() + context)
        assertTrue(ModelEvidenceIssue.UNSUPPORTED_TEXT in wrong.issues); reviewed(wrong)
    }
    @Test fun agreeingCensoredAndAmbiguousNumbersNeverBecomeAutomatic() {
        for (raw in listOf("< 0,1", "1,234", "1.234")) {
            val input = source().map { if (it.spanId == "result") it.copy(raw = raw) else it }
            val output = validate(original.copy(result = ProposedField(raw, listOf("e4"))), input)
            assertTrue(output.issues.isEmpty()); reviewed(output)
        }
    }
    @Test fun reusedSpanIdsFromAnotherSourceCannotValidateAPreviousPage() {
        val baseline = BiochemistryParser().parse("doc", "run", 1, source())
        for (changed in listOf(source().map { if (it.spanId == "result") it.copy(raw = "16") else it },
            source().map { if (it.spanId == "analyte") it.copy(region = SourceRegion(1, 25.0, 220.0, 95.0, 234.0, 600.0, 800.0)) else it },
            source().map { if (it.spanId == "reference") it.copy(method = "OCR") else it })) {
            val output = ProposalEvidenceValidator().validate(ProposalProtocol.compile(1, changed), FieldProposal(listOf(original)), baseline)
            assertTrue(ModelEvidenceIssue.SOURCE_MISMATCH in output.issues)
            assertNull(output.acceptedProposal); reviewed(output)
        }
    }
    @Test fun directTypedProposalsCannotBypassDuplicateOrEmptyCitations() {
        for (ids in listOf(emptyList(), listOf("e4", "e4"))) {
            val output = validate(original.copy(result = ProposedField("15", ids)))
            assertTrue(ModelEvidenceIssue.UNSUPPORTED_TEXT in output.issues)
            assertNull(output.acceptedProposal); reviewed(output)
        }
    }
}
