package br.com.exames.featureresults

import br.com.exames.domain.validation.SourceRegion
import org.junit.Assert.*
import org.junit.Test

class EvidenceProjectionTest {
    @Test fun proportionalPreviewKeepsExactFieldBoundsAndPageEdges() {
        val region = SourceRegion(2, 120.0, 160.0, 360.0, 480.0, 600.0, 800.0)
        assertEquals(RelativeEvidenceBox(.2f, .2f, .6f, .6f), EvidenceProjection.project(region, 1536, 2048))
        val page = region.copy(left = 0.0, top = 0.0, right = 600.0, bottom = 800.0)
        assertEquals(RelativeEvidenceBox(0f, 0f, 1f, 1f), EvidenceProjection.project(page, 300, 400))
    }
    @Test fun uprightFramesAreNotRotatedAgainForAnyPdfOrExifOrientation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val w = if (rotation % 180 == 0) 600.0 else 800.0
            val h = if (rotation % 180 == 0) 800.0 else 600.0
            val region = SourceRegion(1, .2*w, .3*h, .7*w, .8*h, w, h, rotation)
            assertEquals(RelativeEvidenceBox(.2f, .3f, .7f, .8f), EvidenceProjection.project(region, w.toInt(), h.toInt()))
        }
    }
    @Test fun incompatibleFramesAndInvalidPreviewSizesNeverProduceEstimatedHighlights() {
        val region = SourceRegion(1, 10.0, 20.0, 30.0, 40.0, 600.0, 800.0)
        assertNull(EvidenceProjection.project(region, 800, 600))
        assertNull(EvidenceProjection.project(region, 600, 600))
        assertNull(EvidenceProjection.project(region, 0, 800))
        assertNull(EvidenceProjection.project(region, 600, -1))
        assertNotNull(EvidenceProjection.project(region, 600, 801))
        assertNull(EvidenceProjection.project(region, 600, 803))
    }
    @Test fun boundsThatCollapseAtDrawingPrecisionDoNotClaimAVisibleRegion() {
        val region = SourceRegion(1, 999999998.0, 100.0, 1000000000.0, 200.0, 1000000000.0, 1000000000.0)
        assertNull(EvidenceProjection.project(region, 1000, 1000))
    }
}
