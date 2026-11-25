package br.com.exames.app

import br.com.exames.coreui.screens.ExecutionModeUi
import br.com.exames.coreui.screens.PageProgressStatus
import br.com.exames.coreui.screens.PageProgressUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamWorkCodecTest {
    @Test fun inputDataContainsOnlyOpaqueExamIdAndBooleanFlag() {
        val data = ExamWorkCodec.toInputData("exam-opaque-123", useCloud = true)
        assertEquals(setOf(ExamWorkCodec.KEY_EXAM_ID, ExamWorkCodec.KEY_USE_CLOUD), data.keyValueMap.keys)
        assertEquals("exam-opaque-123", data.getString(ExamWorkCodec.KEY_EXAM_ID))
        assertTrue(data.getBoolean(ExamWorkCodec.KEY_USE_CLOUD, false))
    }

    @Test fun progressDataRoundTripsPagesModeAndCounts() {
        val finished = mapOf(
            1 to PageProgressUi(1, PageProgressStatus.COMPLETED, verifiedCount = 5),
            2 to PageProgressUi(2, PageProgressStatus.ISSUE, verifiedCount = 1),
        )
        val data = ExamWorkCodec.toProgressData(
            examId = "exam-opaque-123",
            useCloud = true,
            page = 3,
            totalPages = 4,
            block = 2,
            totalBlocks = 3,
            finishedPages = finished,
        )

        val ui = ExamWorkCodec.toProcessingUi(data)
        assertEquals("Extraindo via nuvem privada", ui.message)
        assertEquals(ExecutionModeUi.CLOUD_PRIVATE_FALLBACK, ui.executionMode)
        assertEquals(3, ui.page)
        assertEquals(4, ui.totalPages)
        assertEquals(2, ui.block)
        assertEquals(3, ui.totalBlocks)
        assertEquals(4, ui.pages.size)
        assertEquals(PageProgressStatus.COMPLETED, ui.pages[0].status)
        assertEquals(5, ui.pages[0].verifiedCount)
        assertEquals(PageProgressStatus.ISSUE, ui.pages[1].status)
        assertEquals(PageProgressStatus.IN_PROGRESS, ui.pages[2].status)
        assertEquals(PageProgressStatus.PENDING, ui.pages[3].status)
    }

    @Test fun emptyProgressDataFallsBackToInitialLocalState() {
        val ui = ExamWorkCodec.toProcessingUi(ExamWorkCodec.toInputData("exam-1", useCloud = false))
        assertEquals("Analisando laudo em PDF", ui.message)
        assertEquals(ExecutionModeUi.LOCAL_OFFLINE, ui.executionMode)
        assertTrue(ui.pages.isEmpty())
    }

    @Test fun errorNoticesAreSafeAndOperational() {
        assertTrue(ExamWorkCodec.errorNotice("PDF_PAGE_LIMIT").contains("limite de páginas"))
        assertTrue(ExamWorkCodec.errorNotice("PDF_OPEN_FAILED").contains("abrir o PDF"))
        assertFalse(ExamWorkCodec.errorNotice("UNKNOWN").contains("UNKNOWN"))
    }
}
