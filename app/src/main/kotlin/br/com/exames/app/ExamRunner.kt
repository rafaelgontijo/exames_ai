package br.com.exames.app

import br.com.exames.datalocal.EncryptedExamHistoryStore
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExamPageOutcome
import br.com.exames.domain.extraction.ExtractExam
import br.com.exames.domain.inference.TextInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ExamRunner(
    private val store: EncryptedExamHistoryStore,
    inference: TextInference,
) {
    private val extractor = ExtractExam(inference)

    suspend fun extract(
        id: String,
        pages: IntRange? = null,
        onProgress: (page: Int, totalPages: Int, block: Int, totalBlocks: Int) -> Unit = { _, _, _, _ -> },
        onPageFinished: (ExamPageOutcome) -> Unit = {},
    ): ExamExtraction {
        val pdf = store.pdf.open(id)
        try {
            val totalPages = pdf.pageCount
            return withContext(Dispatchers.Default) {
                extractor.extract(
                    pages = pages ?: (1..totalPages),
                    read = { page -> pdf.readPage(page).use { it.positionedText() } },
                    onProgress = { page, block, blocks -> onProgress(page, totalPages, block, blocks) },
                    onPageFinished = onPageFinished,
                )
            }
        } finally {
            pdf.closeAsync()
        }
    }
}
