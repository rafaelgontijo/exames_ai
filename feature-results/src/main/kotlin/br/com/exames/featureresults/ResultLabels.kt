package br.com.exames.featureresults

import br.com.exames.coreui.ChipKind
import br.com.exames.domain.model.AssessmentStatus
import br.com.exames.domain.summary.SummaryCompleteness
import br.com.exames.domain.summary.SummaryWarning
import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.domain.validation.OutputBasis

/** Reference comparison wording only: never "normal", "bom", a diagnosis, or a guarantee. */
internal fun statusLabel(s: AssessmentStatus) = when (s) {
    AssessmentStatus.BELOW_RANGE -> "Abaixo da referência informada"
    AssessmentStatus.WITHIN_RANGE -> "Dentro da referência informada"
    AssessmentStatus.ABOVE_RANGE -> "Acima da referência informada"
    AssessmentStatus.NEEDS_REVIEW -> "Precisa de revisão"
    AssessmentStatus.NOT_CLASSIFIABLE -> "Não classificável"
}
/** Review states use the attention chip; red is not used for urgency. */
internal fun statusKind(s: AssessmentStatus) = when (s) {
    AssessmentStatus.WITHIN_RANGE -> ChipKind.INFO
    AssessmentStatus.BELOW_RANGE, AssessmentStatus.ABOVE_RANGE, AssessmentStatus.NEEDS_REVIEW, AssessmentStatus.NOT_CLASSIFIABLE -> ChipKind.REVIEW
}
internal fun completenessLabel(c: SummaryCompleteness) = when (c) {
    SummaryCompleteness.COMPLETE -> "Todas as páginas foram processadas"
    SummaryCompleteness.PARTIAL -> "Leitura parcial: há páginas não concluídas"
    SummaryCompleteness.IN_PROGRESS -> "Leitura em andamento"
    SummaryCompleteness.UNAVAILABLE -> "Leitura indisponível"
}
internal fun warningLabel(w: SummaryWarning) = when (w) {
    SummaryWarning.NO_OBSERVATIONS -> "nenhum campo suportado encontrado"
    SummaryWarning.AMBIGUITIES_PRESENT -> "há ambiguidades a conferir na fonte"
    SummaryWarning.REFERENCE_MISSING -> "há campos sem referência no documento"
    SummaryWarning.UNSUPPORTED_CONTENT -> "há conteúdo não suportado"
    SummaryWarning.PAGES_INCOMPLETE -> "páginas incompletas"
    SummaryWarning.ASSESSMENT_MISSING -> "há campos sem avaliação confiável"
    SummaryWarning.ORPHAN_ASSESSMENT -> "há avaliações sem campo correspondente"
    SummaryWarning.RUN_NOT_COMPLETE -> "execução não concluída"
}
internal fun basisLabel(b: OutputBasis, corrections: Int) = when (b) {
    OutputBasis.AUTOMATIC -> "Saída automática"
    OutputBasis.CORRECTED -> "Reavaliação após correções — $corrections registros de correção"
    OutputBasis.REASSESSED -> "Reavaliação da regra sem nova extração"
}
internal fun modeLabel(m: ExtractionMode) = if (m == ExtractionMode.BASELINE) "Modo sem IA" else "Modo assistido"
