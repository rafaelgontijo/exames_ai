package br.com.exames.domain.summary

import br.com.exames.domain.model.ReasonCode
import br.com.exames.domain.inference.ProposalDisposition
import br.com.exames.domain.validation.*

@JvmInline
value class InferenceParticipation private constructor(val label: String) {
    companion object {
        fun from(run: ProcessingRun, pages: List<PageCheckpoint>) = InferenceParticipation(inferenceParticipation(run, pages))
    }
}

fun inferenceParticipation(run: ProcessingRun, pages: List<PageCheckpoint>): String {
    if(run.outputBasis!=OutputBasis.AUTOMATIC) return "Esta reavaliação não executou novamente o modelo."
    if(run.mode==ExtractionMode.BASELINE) return "Sem IA: nenhuma inferência solicitada."
    val accepted=pages.count { p -> run.inference!=null && p.state==PageState.COMPLETE && p.inferenceOutcome==InferenceOutcome.SUCCEEDED &&
        p.inferenceAttempt?.let { it.disposition==ProposalDisposition.ACCEPTED && it.requestSubmitted && it.configuration==run.inference }==true }
    val fallback=pages.count { it.inferenceOutcome in setOf(InferenceOutcome.FALLBACK,InferenceOutcome.FAILED,InferenceOutcome.CANCELLED) }
    if(run.state in setOf(RunState.QUEUED,RunState.RUNNING)) return "Modo assistido em andamento. Propostas aceitas: $accepted. Páginas com fallback/falha: $fallback."
    if(pages.isNotEmpty() && accepted==pages.size && run.inferenceOutcome==InferenceOutcome.SUCCEEDED)
        return "IA executada: propostas aceitas após validar evidência em $accepted páginas. A comparação continua no avaliador determinístico."
    return "Propostas de IA aceitas: $accepted páginas. Fallback/falha: $fallback páginas. Conclusão do parser não comprova sucesso de IA; participação sem registro de tentativa permanece desconhecida."
}

fun summaryWarning(w: SummaryWarning) = when (w) {
    SummaryWarning.NO_OBSERVATIONS -> "nenhum campo suportado encontrado"
    SummaryWarning.AMBIGUITIES_PRESENT -> "há ambiguidades a conferir na fonte"
    SummaryWarning.REFERENCE_MISSING -> "há campos sem referência no documento"
    SummaryWarning.UNSUPPORTED_CONTENT -> "há conteúdo não suportado"
    SummaryWarning.PAGES_INCOMPLETE -> "páginas incompletas"
    SummaryWarning.ASSESSMENT_MISSING -> "há campos sem avaliação confiável"
    SummaryWarning.ORPHAN_ASSESSMENT -> "há avaliações sem campo correspondente"
    SummaryWarning.RUN_NOT_COMPLETE -> "execução não concluída"
}

fun assessmentReason(code: ReasonCode) = when (code) {
    ReasonCode.AMBIGUOUS_INPUT -> "Há ambiguidade nos dados extraídos ou na associação da referência."
    ReasonCode.UNCONFIRMED -> "A regra desta execução exige confirmação registrada."
    ReasonCode.NO_REFERENCE -> "Não há referência numérica utilizável no documento."
    ReasonCode.REFERENCE_MISMATCH -> "A referência não está associada de forma segura a este resultado."
    ReasonCode.CONTEXT_UNRESOLVED -> "O contexto necessário à referência não foi resolvido."
    ReasonCode.UNIT_UNRESOLVED -> "A unidade está ausente ou é incompatível com a referência."
    ReasonCode.NON_NUMERIC -> "O resultado não é numérico nesta regra."
    ReasonCode.CENSORED_RESULT -> "O resultado contém um comparador e requer revisão."
    ReasonCode.INVALID_REFERENCE -> "A referência não forma um limite numérico válido."
    ReasonCode.BELOW_LOWER_BOUND -> "O valor não atende ao limite inferior da referência do documento."
    ReasonCode.ABOVE_UPPER_BOUND -> "O valor não atende ao limite superior da referência do documento."
    ReasonCode.INSIDE_REFERENCE -> "O valor está nos limites da referência associada no documento."
}
