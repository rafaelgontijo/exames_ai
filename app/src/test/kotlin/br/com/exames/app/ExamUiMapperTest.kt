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

    @Test fun mapsScannedVisualObservationToNeedsReviewAndResolvesFallbackPageInEvidenceUi() {
        val scannedObs = ExamObservation(
            name = ExamCitation("p3vis1n", "Glicose", emptyList()),
            sourceResult = ExamCitation("p3vis1v", "82 mg/dL", emptyList()),
            result = "82",
            unit = "mg/dL",
            reference = ExamCitation("p3vis1f", "70 a 99 mg/dL", emptyList()),
            referenceNeedsReview = true,
            unitCitation = null,
            visualOnly = true,
            page = 3,
        )
        val assessed = ConservativeExamAssessor().assess(scannedObs)
        assertEquals(DocumentaryStatus.NEEDS_REVIEW, assessed.status)
        assertEquals(DocumentaryReason.VISUAL_EXTRACTION_WITHOUT_DIGITAL_TEXT, assessed.reason)

        val evidence = ExamUiMapper.toEvidenceUi("exam-scanned", 5, assessed)
        assertEquals(3, evidence.page)
        assertEquals(5, evidence.totalPages)
        assertEquals("Revisar", evidence.statusLabel)
        assertEquals("Extraído visualmente sem texto digital — confira no laudo", evidence.documentaryNote)
        assertEquals(emptyList<Any>(), evidence.resultBoxes)
        assertEquals(emptyList<Any>(), evidence.referenceBoxes)
    }

    @Test fun mapsEphemeralClinicalContextAndSurfacesAppliedStratumExcerpt() {
        val uiContext = br.com.exames.coreui.screens.ClinicalContextUi(
            sex = br.com.exames.coreui.screens.ContextSexOption.MALE,
            ageInput = "35",
            fasting = br.com.exames.coreui.screens.ContextFastingOption.FASTING,
            pregnancy = br.com.exames.coreui.screens.ContextPregnancyOption.PREGNANT, // ignored for MALE
        )
        val domain = ExamUiMapper.toDomainContext(uiContext)
        assertEquals(BiologicalSex.MALE, domain.sex)
        assertEquals(35, domain.ageYears)
        assertEquals(FastingState.FASTING, domain.fasting)
        assertEquals(PregnancyState.UNSPECIFIED, domain.pregnancy)

        val r = SourceRegion(1, 10.0, 20.0, 80.0, 30.0, 500.0, 800.0)
        val obs = ExamObservation(
            ExamCitation("n1", "Hemoglobina", listOf(r)),
            ExamCitation("v1", "14,8 g/dL", listOf(r)),
            "14,8",
            "g/dL",
            ExamCitation("f1", "Homem: 13,5 a 17,5 g/dL; Mulher: 12,0 a 15,5 g/dL", listOf(r)),
            false,
        )
        val assessed = ConservativeExamAssessor().assess(obs, domain)
        assertEquals(DocumentaryStatus.WITHIN_RANGE, assessed.status)
        val itemUi = ExamUiMapper.toResultItem(1, assessed)
        assertEquals("Na referência", itemUi.statusLabel)
        assertEquals(
            "Dentro da referência informada no documento · Faixa aplicada: Homem: 13,5 a 17,5 g/dL",
            itemUi.documentaryNote,
        )

        val implicitObs = ExamObservation(
            ExamCitation("n2", "Hematócrito", listOf(r)),
            ExamCitation("v2", "44,0 %", listOf(r)),
            "44,0",
            "%",
            ExamCitation("f2", "40,0 a 50,0", listOf(r)),
            false,
        )
        val assessedImplicit = ConservativeExamAssessor().assess(implicitObs)
        val implicitItemUi = ExamUiMapper.toResultItem(1, assessedImplicit)
        assertEquals("Na referência", implicitItemUi.statusLabel)
        assertEquals(
            "Dentro da referência informada no documento · mesma unidade do resultado",
            implicitItemUi.documentaryNote,
        )
    }
}
