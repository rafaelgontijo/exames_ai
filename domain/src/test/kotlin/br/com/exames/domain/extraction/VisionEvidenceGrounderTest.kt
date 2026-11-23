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

    private fun assertNotNullUnit(obs: ExamObservation) {
        assertEquals("mg/dL", obs.unitCitation?.text)
    }

    private fun word(page: Int, id: String, text: String, x: Double, y: Double, right: Double) =
        SourceFragment(id, text, SourceRegion(page, x, y, right, y + 10, 500.0, 800.0), "PDF_TEXT", "1")
}
