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

enum class ExecutionModeUi { LOCAL_OFFLINE, CLOUD_PRIVATE_FALLBACK }

enum class LocalModelStatusUi {
    READY,
    DOWNLOADING,
    NOT_DOWNLOADED,
    INELIGIBLE_HARDWARE,
    INSUFFICIENT_SPACE,
    ERROR,
}

data class HybridSettingsUi(
    val activeModeLabel: String = "IA local no aparelho",
    val isCloudActive: Boolean = false,
    val localModelStatus: LocalModelStatusUi = LocalModelStatusUi.NOT_DOWNLOADED,
    val downloadFraction: Float? = null,
    val downloadStatusMessage: String? = null,
    val canDownloadLocal: Boolean = true,
    val canRemoveLocal: Boolean = false,
    val cloudFallbackOptIn: Boolean = false,
    val downloadOnWifiOnly: Boolean = true,
    val cloudEndpointConfigured: Boolean = false,
)

data class CloudConsentPromptUi(
    val reasonSummary: String,
    val canDownloadLocal: Boolean,
)

data class ProcessingUiState(
    val message: String,
    val page: Int = 0,
    val totalPages: Int = 0,
    val block: Int = 0,
    val totalBlocks: Int = 0,
    val pages: List<PageProgressUi> = emptyList(),
    val cancellable: Boolean = true,
    val executionMode: ExecutionModeUi = ExecutionModeUi.LOCAL_OFFLINE,
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

enum class ContextSexOption { UNSPECIFIED, FEMALE, MALE }

enum class ContextFastingOption { UNSPECIFIED, FASTING, NON_FASTING }

enum class ContextPregnancyOption { UNSPECIFIED, NOT_PREGNANT, PREGNANT }

/** Ephemeral UI state for session-only clinical context; never saved to disk or SavedStateHandle. */
data class ClinicalContextUi(
    val sex: ContextSexOption = ContextSexOption.UNSPECIFIED,
    val ageInput: String = "",
    val fasting: ContextFastingOption = ContextFastingOption.UNSPECIFIED,
    val pregnancy: ContextPregnancyOption = ContextPregnancyOption.UNSPECIFIED,
) {
    val isEmpty: Boolean
        get() = sex == ContextSexOption.UNSPECIFIED &&
            ageInput.isBlank() &&
            fasting == ContextFastingOption.UNSPECIFIED &&
            pregnancy == ContextPregnancyOption.UNSPECIFIED

    fun summaryLabel(): String = buildList {
        when (sex) {
            ContextSexOption.FEMALE -> add("Feminino")
            ContextSexOption.MALE -> add("Masculino")
            ContextSexOption.UNSPECIFIED -> Unit
        }
        ageInput.trim().toIntOrNull()?.let { add("$it anos") }
        when (fasting) {
            ContextFastingOption.FASTING -> add("Em jejum")
            ContextFastingOption.NON_FASTING -> add("Sem jejum")
            ContextFastingOption.UNSPECIFIED -> Unit
        }
        if (sex != ContextSexOption.MALE) {
            when (pregnancy) {
                ContextPregnancyOption.PREGNANT -> add("Gestante")
                ContextPregnancyOption.NOT_PREGNANT -> add("Não gestante")
                ContextPregnancyOption.UNSPECIFIED -> Unit
            }
        }
    }.joinToString(" · ")
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
    val clinicalContext: ClinicalContextUi = ClinicalContextUi(),
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
