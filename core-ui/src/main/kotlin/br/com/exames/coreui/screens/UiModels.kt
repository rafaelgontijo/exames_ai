package br.com.exames.coreui.screens

import br.com.exames.coreui.ChipKind

data class ExamCardItemUi(
    val id: String,
    val title: String,
    val subtitleDate: String,
    val statusLabel: String,
    val statusKind: ChipKind,
    val interrupted: Boolean = false,
)

enum class PageProgressStatus { COMPLETED, IN_PROGRESS, PENDING, ISSUE }

data class PageProgressUi(
    val page: Int,
    val status: PageProgressStatus,
    val verifiedCount: Int = 0,
)

data class ProcessingUiState(
    val message: String,
    val page: Int = 0,
    val totalPages: Int = 0,
    val block: Int = 0,
    val totalBlocks: Int = 0,
    val pages: List<PageProgressUi> = emptyList(),
    val cancellable: Boolean = true,
) {
    val progressFraction: Float?
        get() {
            if (totalPages <= 0) return null
            val completedPages = pages.count { it.status == PageProgressStatus.COMPLETED || it.status == PageProgressStatus.ISSUE }
            val blockContribution = if (totalBlocks > 0) (block.coerceAtLeast(0).toFloat() / totalBlocks).coerceIn(0f, 1f) else 0f
            val raw = (completedPages + blockContribution) / totalPages.toFloat()
            return raw.coerceIn(0.02f, 0.99f)
        }
}

enum class ResultFilterOption { ALL, REVIEW, WITHIN_RANGE, UNCLASSIFIABLE }

data class ResultFilterTabUi(
    val option: ResultFilterOption,
    val label: String,
    val count: Int,
)

data class ResultItemUi(
    val id: String,
    val name: String,
    val valueWithUnit: String,
    val rawReference: String?,
    val statusLabel: String,
    val statusKind: ChipKind,
    val documentaryNote: String,
    val page: Int,
    val hasReferenceRegions: Boolean,
)

data class ResultPageGroupUi(
    val page: Int,
    val issueMessage: String?,
    val items: List<ResultItemUi>,
)

data class ResultsUiState(
    val examId: String,
    val dateLabel: String,
    val pageCount: Int,
    val totalCount: Int,
    val reviewCount: Int,
    val partial: Boolean,
    val notice: String? = null,
    val activeFilter: ResultFilterOption = ResultFilterOption.ALL,
    val filters: List<ResultFilterTabUi> = emptyList(),
    val pages: List<ResultPageGroupUi> = emptyList(),
    val primaryEvidenceItemId: String? = null,
)

data class EvidenceBoxUi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pageWidth: Float,
    val pageHeight: Float,
)

data class EvidenceUiState(
    val examId: String,
    val page: Int,
    val totalPages: Int,
    val itemName: String,
    val valueWithUnit: String,
    val statusLabel: String,
    val statusKind: ChipKind,
    val rawReference: String?,
    val documentaryNote: String,
    val resultBoxes: List<EvidenceBoxUi>,
    val referenceBoxes: List<EvidenceBoxUi>,
)
