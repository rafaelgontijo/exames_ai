package br.com.exames.app

import br.com.exames.coreui.ChipKind
import br.com.exames.coreui.screens.*
import br.com.exames.datalocal.ExamHistoryEntry
import br.com.exames.domain.extraction.*
import br.com.exames.domain.inference.InferenceFailure
import br.com.exames.domain.validation.SourceRegion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object ExamUiMapper {
    private val ptBr = Locale.forLanguageTag("pt-BR")
    private val shortDateFormatter = DateTimeFormatter.ofPattern("d MMM", ptBr)
    private val longDateFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM", ptBr)

    fun formatShortDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        shortDateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone)).replace(".", "")

    fun formatLongDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        longDateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun toHistoryCard(entry: ExamHistoryEntry, zone: ZoneId = ZoneId.systemDefault()): ExamCardItemUi {
        val dateText = formatLongDate(entry.importedAtEpochMillis, zone)
        val subtitle = if (entry.pageCount > 0) {
            val pages = if (entry.pageCount == 1) "1 página" else "${entry.pageCount} páginas"
            "$dateText · $pages"
        } else {
            dateText
        }
        val (statusLabel, statusKind) = when {
            entry.interrupted -> "Leitura interrompida" to ChipKind.REVIEW
            entry.reviewCount > 0 -> {
                val label = if (entry.reviewCount == 1) "1 para revisar" else "${entry.reviewCount} para revisar"
                label to ChipKind.REVIEW
            }
            entry.totalCount == 0 -> "Sem resultados" to ChipKind.REVIEW
            entry.withinRangeCount > 0 && entry.unclassifiableCount == 0 -> "Na referência" to ChipKind.INFO
            else -> "${entry.totalCount} extraídos" to ChipKind.INFO
        }
        return ExamCardItemUi(
            id = entry.id,
            title = "Laudo laboratorial · PDF",
            subtitleDate = subtitle,
            statusLabel = statusLabel,
            statusKind = statusKind,
            interrupted = entry.interrupted,
        )
    }

    fun toResultsUi(
        entry: ExamHistoryEntry,
        assessed: AssessedExam,
        activeFilter: ResultFilterOption,
        notice: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ResultsUiState {
        val counts = assessed.counts
        val domainFilter = when (activeFilter) {
            ResultFilterOption.ALL -> ExamStatusFilter.ALL
            ResultFilterOption.REVIEW -> ExamStatusFilter.NEEDS_REVIEW
            ResultFilterOption.WITHIN_RANGE -> ExamStatusFilter.WITHIN_RANGE
            ResultFilterOption.UNCLASSIFIABLE -> ExamStatusFilter.NOT_CLASSIFIABLE
        }
        val filters = buildList {
            if (counts.total > 0) {
                add(ResultFilterTabUi(ResultFilterOption.ALL, "Todos", counts.total))
                add(ResultFilterTabUi(ResultFilterOption.REVIEW, "Revisar", counts.reviewCount))
                add(ResultFilterTabUi(ResultFilterOption.WITHIN_RANGE, "Na referência", counts.withinRangeCount))
                if (counts.unclassifiableCount > 0) {
                    add(ResultFilterTabUi(ResultFilterOption.UNCLASSIFIABLE, "Sem faixa", counts.unclassifiableCount))
                }
            }
        }
        val filteredPages = assessed.filter(domainFilter).map { page ->
            ResultPageGroupUi(
                page = page.page,
                issueMessage = if (page.issues.isNotEmpty()) pageNotice(page.issues, page.inferenceFailures) else null,
                items = page.observations.map { toResultItem(page.page, it) },
            )
        }
        return ResultsUiState(
            examId = entry.id,
            dateLabel = formatShortDate(entry.importedAtEpochMillis, zone),
            pageCount = assessed.pages.size,
            totalCount = counts.total,
            reviewCount = counts.reviewCount,
            partial = assessed.partial,
            notice = notice,
            activeFilter = activeFilter,
            filters = filters,
            pages = filteredPages,
            primaryEvidenceItemId = assessed.firstReviewObservation()?.observation?.sourceResult?.id,
        )
    }

    fun toResultItem(page: Int, assessed: AssessedObservation): ResultItemUi {
        val row = assessed.observation
        return ResultItemUi(
            id = row.sourceResult.id,
            name = row.name.text,
            valueWithUnit = listOfNotNull(row.result, row.unit).joinToString(" "),
            rawReference = row.reference?.text,
            statusLabel = statusLabel(assessed.status),
            statusKind = statusChipKind(assessed.status),
            documentaryNote = reasonNote(assessed.reason),
            page = page,
            hasReferenceRegions = row.reference?.regions.orEmpty().isNotEmpty(),
        )
    }

    fun toEvidenceUi(
        examId: String,
        totalPages: Int,
        assessed: AssessedObservation,
    ): EvidenceUiState {
        val row = assessed.observation
        val resultRegions = row.name.regions + row.sourceResult.regions + row.unitCitation?.regions.orEmpty()
        val referenceRegions = row.reference?.regions.orEmpty()
        val page = (resultRegions.firstOrNull() ?: referenceRegions.firstOrNull())?.page ?: 1
        return EvidenceUiState(
            examId = examId,
            page = page,
            totalPages = totalPages.coerceAtLeast(page),
            itemName = row.name.text,
            valueWithUnit = listOfNotNull(row.result, row.unit).joinToString(" "),
            statusLabel = statusLabel(assessed.status),
            statusKind = statusChipKind(assessed.status),
            rawReference = row.reference?.text,
            documentaryNote = reasonNote(assessed.reason),
            resultBoxes = resultRegions.map(::toBox),
            referenceBoxes = referenceRegions.map(::toBox),
        )
    }

    fun statusLabel(status: DocumentaryStatus): String = when (status) {
        DocumentaryStatus.WITHIN_RANGE -> "Na referência"
        DocumentaryStatus.NEEDS_REVIEW -> "Revisar"
        DocumentaryStatus.NOT_CLASSIFIABLE -> "Sem faixa"
    }

    fun statusChipKind(status: DocumentaryStatus): ChipKind = when (status) {
        DocumentaryStatus.WITHIN_RANGE -> ChipKind.INFO
        DocumentaryStatus.NEEDS_REVIEW -> ChipKind.REVIEW
        DocumentaryStatus.NOT_CLASSIFIABLE -> ChipKind.INFO
    }

    fun reasonNote(reason: DocumentaryReason): String = when (reason) {
        DocumentaryReason.WITHIN_DOCUMENT_RANGE -> "Dentro da referência informada no documento"
        DocumentaryReason.ABOVE_DOCUMENT_RANGE -> "Acima da faixa informada no documento"
        DocumentaryReason.BELOW_DOCUMENT_RANGE -> "Abaixo da faixa informada no documento"
        DocumentaryReason.UNCERTAIN_REFERENCE_LINK -> "Confira a referência no laudo."
        DocumentaryReason.MULTIPLE_OR_CONDITIONAL_REFERENCE -> "Referência com múltiplos critérios ou condicional — confira no laudo"
        DocumentaryReason.UNIT_MISMATCH_OR_MISSING -> "Unidade divergente ou ausente — confira no laudo"
        DocumentaryReason.CENSORED_OR_AMBIGUOUS_VALUE -> "Valor com símbolo ou formato ambíguo — confira no laudo"
        DocumentaryReason.INVALID_REFERENCE_SYNTAX -> "Formato de referência não comparável — confira no laudo"
        DocumentaryReason.NO_REFERENCE -> "Sem faixa de referência informada no documento"
        DocumentaryReason.NON_NUMERIC_RESULT -> "Resultado qualitativo preservado como no laudo"
    }

    fun pageNotice(issues: Set<ExamIssue>, failures: Set<InferenceFailure>): String = when {
        InferenceFailure.UNSUPPORTED_DEVICE in failures -> "O aparelho não oferece suporte ao modelo de IA."
        InferenceFailure.MODEL_SPACE in failures -> "Espaço insuficiente para preparar o modelo de IA."
        ExamIssue.PAGE_UNREADABLE in issues -> "Não foi possível ler esta página."
        ExamIssue.INSUFFICIENT_POSITIONED_TEXT in issues -> "Confira o texto e os resultados desta página no laudo."
        ExamIssue.INFERENCE_FAILED in issues || ExamIssue.INVALID_RESPONSE in issues -> "A IA não concluiu todos os trechos desta página."
        ExamIssue.INPUT_LIMIT in issues -> "Um trecho desta página excedeu o limite da IA."
        else -> "Confira os campos e possíveis omissões nesta página."
    }

    private fun toBox(region: SourceRegion): EvidenceBoxUi = EvidenceBoxUi(
        left = region.left.toFloat(),
        top = region.top.toFloat(),
        right = region.right.toFloat(),
        bottom = region.bottom.toFloat(),
        pageWidth = region.pageWidth.toFloat(),
        pageHeight = region.pageHeight.toFloat(),
    )
}
