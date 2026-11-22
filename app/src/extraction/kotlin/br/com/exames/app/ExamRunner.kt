package br.com.exames.app

import br.com.exames.datalocal.CurrentExamStore
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExtractExam
import br.com.exames.domain.inference.TextInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ExamRunner(private val store: CurrentExamStore, inference: TextInference) {
    private val extractor = ExtractExam(inference)

    suspend fun extract(
        id: String,
        pages: IntRange? = null,
        onProgress: (page: Int, totalPages: Int, block: Int, totalBlocks: Int) -> Unit = { _, _, _, _ -> },
    ): ExamExtraction {
        val pdf = store.pdf.open(id)
        try {
            return withContext(Dispatchers.Default) {
                extractor.extract(pages ?: (1..pdf.pageCount),
                    read = { page -> pdf.readPage(page).use { it.positionedText() } },
                    onProgress = { page, block, blocks -> onProgress(page, pdf.pageCount, block, blocks) },
                )
            }
        } finally {
            pdf.closeAsync()
        }
    }
}
