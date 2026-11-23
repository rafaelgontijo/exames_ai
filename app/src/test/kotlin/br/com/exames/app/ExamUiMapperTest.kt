package br.com.exames.app

import br.com.exames.coreui.ChipKind
import br.com.exames.coreui.screens.ResultFilterOption
import br.com.exames.datalocal.ExamHistoryEntry
import br.com.exames.domain.extraction.*
import br.com.exames.domain.validation.SourceRegion
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamUiMapperTest {
    @Test fun mapsHistoryCardsAndFilteredResultsWithConservativeStatus() {
        val entry = ExamHistoryEntry(
            id = "exam-1",
            importedAtEpochMillis = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli(),
            pageCount = 2,
            totalCount = 3,
            reviewCount = 1,
            withinRangeCount = 1,
            unclassifiableCount = 1,
            hasPageIssues = false,
            interrupted = false,
        )
        val card = ExamUiMapper.toHistoryCard(entry, ZoneOffset.UTC)
        assertEquals("1 para revisar", card.statusLabel)
        assertEquals(ChipKind.REVIEW, card.statusKind)

        val r = SourceRegion(1, 10.0, 20.0, 80.0, 30.0, 500.0, 800.0)
        val extraction = ExamExtraction(
            listOf(
                ExamPageOutcome(
                    page = 1,
                    observations = listOf(
                        ExamObservation(ExamCitation("n1", "Glicose", listOf(r)), ExamCitation("v1", "118 mg/dL", listOf(r)), "118", "mg/dL", ExamCitation("f1", "70 a 99 mg/dL", listOf(r)), false),
                        ExamObservation(ExamCitation("n2", "Hemoglobina", listOf(r)), ExamCitation("v2", "14,2 g/dL", listOf(r)), "14,2", "g/dL", ExamCitation("f2", "13,5 a 17,5 g/dL", listOf(r)), false),
                        ExamObservation(ExamCitation("n3", "Aspecto", listOf(r)), ExamCitation("v3", "Límpido", listOf(r)), "Límpido", null, null, false),
                    ),
                    issues = emptySet(),
                    inferenceFailures = emptySet(),
                ),
            ),
        )
        val assessed = ExamSummaryProjection.project(extraction)
        val uiAll = ExamUiMapper.toResultsUi(entry, assessed, ResultFilterOption.ALL, null, ZoneOffset.UTC)
        assertEquals(4, uiAll.filters.size)
        assertEquals(listOf("Todos", "Revisar", "Na referência", "Sem faixa"), uiAll.filters.map { it.label })
        assertEquals("v1", uiAll.primaryEvidenceItemId)

        val uiReview = ExamUiMapper.toResultsUi(entry, assessed, ResultFilterOption.REVIEW, null, ZoneOffset.UTC)
        assertEquals(listOf("v1"), uiReview.pages.flatMap { it.items }.map { it.id })
    }
}
