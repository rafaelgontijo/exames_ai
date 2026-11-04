package br.com.exames.featureresults

import br.com.exames.domain.inference.ProposalDisposition
import br.com.exames.domain.validation.*

internal fun inferenceParticipation(run: ProcessingRun, pages: List<PageCheckpoint>): String {
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
