package br.com.exames.domain.extraction

import br.com.exames.domain.validation.SourceRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExamSummaryProjectionTest {
    @Test fun aggregatesCountsAndFiltersInDocumentOrderPreservingRawEvidence() {
        val r1 = obs(1, "v1", "Glicose", "118", "mg/dL", "70 a 99 mg/dL")
        val r2 = obs(1, "v2", "Hemoglobina", "14,2", "g/dL", "13,5 a 17,5 g/dL")
        val r3 = obs(2, "v3", "Observação qualitativa", "Ausente", null, null)
        val r4 = obs(2, "v4", "Colesterol LDL", "162", "mg/dL", "Desejável: < 130 mg/dL")
        val extraction = ExamExtraction(
            pages = listOf(
                ExamPageOutcome(1, listOf(r1, r2), emptySet(), emptySet()),
                ExamPageOutcome(2, listOf(r3, r4), setOf(ExamIssue.INSUFFICIENT_POSITIONED_TEXT), emptySet()),
            ),
        )

        val projected = ExamSummaryProjection.project(extraction)
        assertEquals(4, projected.counts.total)
        assertEquals(2, projected.counts.reviewCount)
        assertEquals(1, projected.counts.withinRangeCount)
        assertEquals(1, projected.counts.unclassifiableCount)
        assertEquals(true, projected.counts.hasPageIssues)
        assertEquals("v1", projected.firstReviewObservation()?.observation?.sourceResult?.id)

        val reviewOnly = projected.filter(ExamStatusFilter.NEEDS_REVIEW)
        assertEquals(listOf("v1", "v4"), reviewOnly.flatMap { it.observations }.map { it.observation.sourceResult.id })
        assertEquals("Desejável: < 130 mg/dL", reviewOnly.last().observations.single().observation.reference?.text)

        val withinOnly = projected.filter(ExamStatusFilter.WITHIN_RANGE)
        assertEquals(listOf("v2"), withinOnly.flatMap { it.observations }.map { it.observation.sourceResult.id })

        val unclassifiableOnly = projected.filter(ExamStatusFilter.NOT_CLASSIFIABLE)
        assertEquals(listOf("v3"), unclassifiableOnly.flatMap { it.observations }.map { it.observation.sourceResult.id })
    }

    private fun obs(page: Int, id: String, name: String, value: String, unit: String?, reference: String?): ExamObservation {
        val region = SourceRegion(page, 10.0, 20.0, 80.0, 30.0, 500.0, 800.0)
        return ExamObservation(
            name = ExamCitation("n-$id", name, listOf(region)),
            sourceResult = ExamCitation(id, listOfNotNull(value, unit).joinToString(" "), listOf(region)),
            result = value,
            unit = unit,
            reference = reference?.let { ExamCitation("f-$id", it, listOf(region)) },
            referenceNeedsReview = false,
        )
    }
}
