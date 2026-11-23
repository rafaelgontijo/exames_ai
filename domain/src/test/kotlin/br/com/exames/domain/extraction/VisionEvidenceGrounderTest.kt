package br.com.exames.domain.extraction

import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.SourceRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisionEvidenceGrounderTest {
    private val band0 = SourceRegion(1, 0.0, 0.0, 500.0, 420.0, 500.0, 800.0)
    private val band1 = SourceRegion(1, 0.0, 320.0, 500.0, 740.0, 500.0, 800.0)

    @Test fun anchorsDigitalValuesResolvesRegionsAndFiltersHallucinations() {
        val frags = listOf(
            word(1, "n1", "Glicose", 20.0, 100.0, 90.0),
            word(1, "v1", "82", 180.0, 100.0, 200.0),
            word(1, "u1", "mg/dL", 205.0, 100.0, 245.0),
            word(1, "f1", "70", 320.0, 100.0, 340.0),
            word(1, "f2", "a", 344.0, 100.0, 352.0),
            word(1, "f3", "99", 356.0, 100.0, 376.0),
            word(1, "f4", "mg/dL", 380.0, 100.0, 420.0),
        )
        val proposals = listOf(
            VisionProposal("Glicose", "82", "mg/dL", "70 a 99 mg/dL", false, 0, band0),
            VisionProposal("Inventado", "999,9", "U/L", "10 a 20 U/L", false, 0, band0),
        )

        val grounded = VisionEvidenceGrounder.ground(1, proposals, frags)
        assertEquals(1, grounded.observations.size)
        assertTrue(ExamIssue.EVIDENCE_NEEDS_REVIEW in grounded.issues)
        val obs = grounded.observations.single()
        assertFalse(obs.visualOnly)
        assertEquals(1, obs.page)
        assertEquals("82", obs.result)
        assertEquals("70 a 99 mg/dL", obs.reference?.text)
        assertFalse(obs.referenceNeedsReview)
        assertEquals(listOf(100.0), obs.sourceResult.regions.map { it.top })
        assertEquals(listOf(100.0), obs.name.regions.map { it.top })
        assertTrue(obs.reference!!.regions.isNotEmpty())
        assertNotNullUnit(obs)
    }

    @Test fun enforcesBoundarySafeNumericMatchingSoSubstringsDoNotGround() {
        val frags = listOf(
            word(1, "n1", "Item X", 20.0, 100.0, 90.0),
            word(1, "v1", "114,25", 180.0, 100.0, 220.0),
        )
        val hallucinatedSubstring = VisionProposal("Item X", "14,2", null, null, false, 0, band0)
        val grounded = VisionEvidenceGrounder.ground(1, listOf(hallucinatedSubstring), frags)
        assertTrue(grounded.observations.isEmpty())
        assertTrue(ExamIssue.EVIDENCE_NEEDS_REVIEW in grounded.issues)
    }

    @Test fun dropsUngroundedReferenceAndMarksNeedsReviewWithoutLosingGroundedResult() {
        val frags = listOf(
            word(1, "n1", "Creatinina", 20.0, 120.0, 100.0),
            word(1, "v1", "0,95", 180.0, 120.0, 215.0),
            word(1, "u1", "mg/dL", 220.0, 120.0, 260.0),
        )
        val proposal = VisionProposal("Creatinina", "0,95", "mg/dL", "0,70 a 1,30 mg/dL", false, 0, band0)
        val grounded = VisionEvidenceGrounder.ground(1, listOf(proposal), frags)
        assertEquals(1, grounded.observations.size)
        val obs = grounded.observations.single()
        assertEquals("0,95", obs.result)
        assertNull(obs.reference)
        assertTrue(obs.referenceNeedsReview)
        assertTrue(ExamIssue.EVIDENCE_NEEDS_REVIEW in grounded.issues)
    }

    @Test fun preservesScannedPageProposalsStrictlyAsVisualOnlyNeedsReview() {
        val proposals = listOf(
            VisionProposal("Glicose", "82", "mg/dL", "70 a 99 mg/dL", false, 0, band0),
            VisionProposal("Glicose", "82", "mg/dL", "70 a 99 mg/dL", false, 1, band1),
        )
        val grounded = VisionEvidenceGrounder.ground(2, proposals, emptyList())
        assertEquals(1, grounded.observations.size)
        assertTrue(ExamIssue.INSUFFICIENT_POSITIONED_TEXT in grounded.issues)
        val obs = grounded.observations.single()
        assertTrue(obs.visualOnly)
        assertTrue(obs.referenceNeedsReview)
        assertEquals(2, obs.page)
        assertTrue(obs.name.regions.isEmpty())
        assertTrue(obs.sourceResult.regions.isEmpty())
        assertEquals("70 a 99 mg/dL", obs.reference?.text)
    }

    @Test fun groundsSplitBaselinePunctuationThousandsDecimalAndMergesMissingUnitAcrossBands() {
        val frags = listOf(
            word(1, "n1", "Exame A", 20.0, 100.0, 90.0),
            word(1, "v1", "1.250,5", 180.0, 100.0, 225.0),
            word(1, "u1", "U/L", 230.0, 100.0, 255.0),
            // Split baseline comma fragments on reference: "1.000 , 0" a "2.500 , 5"
            SourceFragment("f1", "1.000", SourceRegion(1, 300.0, 100.0, 330.0, 112.0, 500.0, 800.0), "PDF_TEXT", "1", spaceBefore = true),
            SourceFragment("f2", ",", SourceRegion(1, 331.0, 112.0, 333.0, 115.0, 500.0, 800.0), "PDF_TEXT", "1", spaceBefore = false),
            SourceFragment("f3", "0", SourceRegion(1, 334.0, 100.0, 340.0, 112.0, 500.0, 800.0), "PDF_TEXT", "1", spaceBefore = false),
            word(1, "f4", "a", 345.0, 100.0, 353.0),
            SourceFragment("f5", "2.500", SourceRegion(1, 358.0, 100.0, 388.0, 112.0, 500.0, 800.0), "PDF_TEXT", "1", spaceBefore = true),
            SourceFragment("f6", ",", SourceRegion(1, 389.0, 112.0, 391.0, 115.0, 500.0, 800.0), "PDF_TEXT", "1", spaceBefore = false),
            SourceFragment("f7", "5", SourceRegion(1, 392.0, 100.0, 398.0, 112.0, 500.0, 800.0), "PDF_TEXT", "1", spaceBefore = false),
            word(1, "f8", "U/L", 403.0, 100.0, 428.0),
        )
        // Band 0 missed unit; overlapping Band 1 captured unit. Both must merge cleanly.
        val proposals = listOf(
            VisionProposal("Exame A", "1.250,5", null, "1.000,0 a 2.500,5 U/L", false, 0, band0),
            VisionProposal("Exame A", "1.250,5", "U/L", "1.000,0 a 2.500,5 U/L", false, 1, band1),
        )
        val grounded = VisionEvidenceGrounder.ground(1, proposals, frags)
        assertEquals(1, grounded.observations.size)
        val obs = grounded.observations.single()
        assertEquals("U/L", obs.unit)
        assertEquals("1.000,0 a 2.500,5 U/L", obs.reference?.text)
        assertFalse(obs.referenceNeedsReview)
        assertTrue(obs.reference!!.regions.isNotEmpty())
    }

    @Test fun letsAssessorEvaluateGroundedReferenceEvenWhenModelFlaggedNeedsReview() {
        val frags = listOf(
            word(1, "n1", "Exame B", 20.0, 100.0, 90.0),
            word(1, "v1", "14,2", 180.0, 100.0, 210.0),
            word(1, "u1", "g/dL", 215.0, 100.0, 245.0),
            word(1, "f1", "Homem:", 280.0, 100.0, 325.0),
            word(1, "f2", "13,5", 330.0, 100.0, 355.0),
            word(1, "f3", "a", 360.0, 100.0, 368.0),
            word(1, "f4", "17,5", 372.0, 100.0, 398.0),
            word(1, "f5", "g/dL;", 402.0, 100.0, 435.0),
            word(1, "f6", "Mulher:", 280.0, 114.0, 325.0),
            word(1, "f7", "12,0", 330.0, 114.0, 355.0),
            word(1, "f8", "a", 360.0, 114.0, 368.0),
            word(1, "f9", "15,5", 372.0, 114.0, 398.0),
            word(1, "f10", "g/dL", 402.0, 114.0, 435.0),
        )
        val proposal = VisionProposal(
            name = "Exame B",
            result = "14,2",
            unit = "g/dL",
            reference = "Homem: 13,5 a 17,5 g/dL; Mulher: 12,0 a 15,5 g/dL",
            needsReview = true,
            bandIndex = 0,
            bandRegion = band0,
        )
        val grounded = VisionEvidenceGrounder.ground(1, listOf(proposal), frags)
        val obs = grounded.observations.single()
        assertFalse(obs.referenceNeedsReview)
        val assessor = ConservativeExamAssessor()
        // Without context -> deterministically NEEDS_REVIEW (MULTIPLE_OR_CONDITIONAL_REFERENCE)
        assertEquals(DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE, assessor.assess(obs).reason)
        // With MALE context -> WITHIN_RANGE
        assertEquals(DocumentaryStatus.WITHIN_RANGE, assessor.assess(obs, ClinicalContext(sex = BiologicalSex.MALE)).status)
    }

    private fun assertNotNullUnit(obs: ExamObservation) {
        assertEquals("mg/dL", obs.unitCitation?.text)
    }

    private fun word(page: Int, id: String, text: String, x: Double, y: Double, right: Double) =
        SourceFragment(id, text, SourceRegion(page, x, y, right, y + 10, 500.0, 800.0), "PDF_TEXT", "1")
}
