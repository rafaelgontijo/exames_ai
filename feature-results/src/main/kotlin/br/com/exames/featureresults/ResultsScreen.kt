package br.com.exames.featureresults

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*

@Composable
fun ResultsScreen(runId: String, onBack: () -> Unit, model: ResultsViewModel = viewModel()) {
    LaunchedEffect(runId) { model.open(runId) }
    val state by model.state.collectAsStateWithLifecycle()
    state.draft?.let { ReviewEditor(it, state.reviewBusy, state.message, model::updateDraft, model::dismissDraft, model::saveDraft) }
    state.report?.let { ValidationReportDialog(it, state.pairedReport, state.run, state.checkpoints, model::closeReport) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.previewPage, state.selectedEvidence, state.rows.size) {
        if (state.previewPage != null && state.run != null) {
            val sourceIndex = 3 + state.rows.size + (if (state.loading) 1 else 0) +
                (if (state.message != null) 1 else 0) + (if (state.unavailable) 1 else 0)
            listState.animateScrollToItem(sourceIndex)
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(24.dp).testTag("results-list"), state = listState,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Revisão dos resultados", style = MaterialTheme.typography.headlineMedium) }
        item { TextButton(onClick = onBack) { Text("Voltar") } }
        if (state.loading) item { CircularProgressIndicator() }
        state.message?.let { item { Text(it) } }
        if (state.unavailable) item { TextButton(onClick = { model.open(runId) }) { Text("Tentar reabrir execução") } }
        state.run?.let { run ->
            item {
                Text(if (run.mode == ExtractionMode.BASELINE) "Modo sem IA" else "Modo assistido")
                Text(inferenceParticipation(run,state.checkpoints))
                Text(when (run.state) {
                    RunState.QUEUED -> "Aguardando processamento"; RunState.RUNNING -> "Em processamento"
                    RunState.COMPLETE -> "Execução concluída"; RunState.PARTIAL -> "Execução parcial — há páginas com falha"
                    RunState.FAILED -> "Execução com falha"; RunState.CANCELLED -> "Execução cancelada"
                })
                Text("Páginas concluídas: ${state.checkpoints.count { it.state == PageState.COMPLETE }}. Falhas: ${state.checkpoints.count { it.state == PageState.FAILED }}.")
                for(page in state.checkpoints.filter { it.state==PageState.FAILED }) Text("Página ${page.page}: " + when(page.errorCode) {
                    "PAGE_UNSUPPORTED_LAYOUT" -> "o layout não foi identificado com segurança. Confira a fonte original."
                    "PAGE_NO_SUPPORTED_OBSERVATIONS" -> "nenhum dos cinco analitos pôde ser extraído com segurança."
                    else -> "falha no processamento. Confira a fonte ou tente uma nova execução."
                })
                Text(when (run.outputBasis) {
                    OutputBasis.AUTOMATIC -> "Saída automática"
                    OutputBasis.CORRECTED -> "Reavaliação após correções — ${run.correctionIds.size} registros de correção"
                    OutputBasis.REASSESSED -> "Reavaliação da regra sem nova extração"
                })
                Text("A sinalização de referência não define diagnóstico ou urgência.")
                if (run.outputBasis != OutputBasis.AUTOMATIC) Text("Esta saída não executou novamente OCR ou IA. As métricas originais permanecem na execução de origem.")
                run.parentRunId?.let { parent -> TextButton(onClick = { model.open(parent) }, enabled = !state.reviewBusy) { Text("Abrir execução de origem") } }
                if (run.state !in setOf(RunState.QUEUED, RunState.RUNNING)) {
                    TextButton(onClick = model::reevaluate, enabled = !state.reviewBusy, modifier = Modifier.testTag("reevaluate")) { Text("Criar reavaliação com correções salvas") }
                    TextButton(onClick = model::reextract, enabled = !state.reviewBusy, modifier = Modifier.testTag("reextract")) { Text("Nova extração sem IA da fonte original") }
                }
                if (state.reviewBusy) CircularProgressIndicator()
                if (state.rows.isEmpty()) Text("Nenhuma observação disponível nesta execução.")
            }
            items(state.rows, key = { it.observation.id }) { row ->
                ObservationResult(row, run.ruleVersion, model::showPage, model::showEvidence, run.outputBasis,
                    if (!state.reviewBusy && run.state !in setOf(RunState.QUEUED, RunState.RUNNING)) model::editCorrection else null)
            }
            item(key = "source-preview") {
                Text("Cópia privada do documento", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (page in 1..(state.pageCount ?: 0)) TextButton(onClick = { model.showPage(page) }) { Text("Página $page") }
                }
                if (state.previewBusy) CircularProgressIndicator()
                state.preview?.let { bitmap ->
                    EvidenceSourcePreview(bitmap, requireNotNull(state.previewPage), state.selectedEvidence)
                }
                if (state.previewPage != null) TextButton(onClick = model::closePreview) { Text("Fechar página") }
            }
            item { Text("Gabaritos associados", style = MaterialTheme.typography.titleMedium)
                if (state.groundTruth.isEmpty()) Text("Nenhum gabarito associado a este documento.")
                if(state.corpusCase==null) Text("Relatórios por estrato e A/B exigem os metadados e o gabarito de um pacote do corpus.")
                else {
                    Text("Selecione explicitamente as execuções para A/B. Devem usar a mesma fonte, parser e regra.")
                    for(candidate in state.relatedRuns.filter { it.outputBasis==OutputBasis.AUTOMATIC && it.state !in setOf(RunState.QUEUED,RunState.RUNNING) }) {
                        TextButton(onClick={ model.selectPair(candidate.mode,candidate.runId) },enabled=!state.reviewBusy) {
                            val chosen=if(candidate.mode==ExtractionMode.BASELINE) state.baselineId==candidate.runId else state.assistedId==candidate.runId
                            Text("${if(chosen) "Selecionada" else "Selecionar"} ${if(candidate.mode==ExtractionMode.BASELINE) "sem IA" else "assistida"} · ${candidate.startedAt} · ${candidate.runId}")
                        }
                    }
                }
            }
            items(state.groundTruth, key = { it.groundTruth.caseId + ":" + it.groundTruth.version }) { snapshot ->
                GroundTruthResult(snapshot) { observationId, field, page ->
                    model.showGroundTruthEvidence(snapshot.groundTruth.caseId, snapshot.groundTruth.version, observationId, field, page)
                }
                TextButton(onClick = { model.editGroundTruth(snapshot.sha256) }, enabled = !state.reviewBusy,
                    modifier = Modifier.testTag("revise-truth-${snapshot.sha256}")) { Text("Revisar este gabarito em nova versão") }
                if (!snapshot.groundTruth.hasReviewedMetadata()) Text("Gabarito pendente: excluído das comparações e dos denominadores de acerto.")
                TextButton(onClick = { model.compare(snapshot.sha256) }, enabled = !state.reviewBusy && snapshot.groundTruth.hasReviewedMetadata() && run.state !in setOf(RunState.QUEUED, RunState.RUNNING),
                    modifier = Modifier.testTag("compare-truth-${snapshot.sha256}")) {
                    Text("Comparar execução com gabarito versão ${snapshot.groundTruth.version}")
                }
                snapshot.parentSha256?.let { Text("Revisão vinculada ao gabarito anterior: $it") }
                if(state.corpusCase!=null) {
                    TextButton(onClick={ model.showReport(snapshot.sha256) },enabled=!state.reviewBusy && run.state !in setOf(RunState.QUEUED,RunState.RUNNING),
                        modifier=Modifier.testTag("report-${snapshot.sha256}")) { Text("Relatório desta execução com gabarito ${snapshot.groundTruth.version}") }
                    TextButton(onClick={ model.showReport(snapshot.sha256,true) },enabled=!state.reviewBusy && snapshot.groundTruth.hasReviewedMetadata() && state.baselineId!=null && state.assistedId!=null,
                        modifier=Modifier.testTag("paired-report-${snapshot.sha256}")) { Text("Comparar os modos selecionados com esta versão") }
                }
            }
            item { Text("Comparações salvas", style = MaterialTheme.typography.titleMedium)
                if (state.comparisons.isEmpty()) Text("Nenhuma comparação salva nesta execução.") }
            items(state.comparisons, key = { it.comparisonId }) { comparison ->
                Text(if (comparison.kind == MeasurementKind.END_TO_END) "Comparação ponta a ponta" else "Ensaio isolado do avaliador")
                Text("Gabarito versão ${comparison.groundTruthVersion}. Diferenças: ${comparison.differences.size}. Ausentes: ${comparison.missingObservationIds.size}. Extras: ${comparison.extraObservationIds.size}.")
                for (difference in comparison.differences) {
                    Text("Diferença em ${fieldLabel(difference.field.removePrefix("raw.").removePrefix("canonical."))}")
                    Text("Esperado registrado: ${difference.expected ?: "ausente"}")
                    Text("Obtido registrado: ${difference.actual ?: "ausente"}")
                    val sources = differenceEvidence(comparison, difference, run, state.rows, state.groundTruth)
                    sources.original?.let { source -> for (page in source.pages) {
                        TextButton(onClick = { model.showEvidence(source.observationId, source.field, page) }) {
                            Text("Destacar fonte extraída da diferença — página $page")
                        }
                    } }
                    sources.expected?.let { source -> for (page in source.pages) {
                        TextButton(onClick = { sources.truth?.groundTruth?.let { truth ->
                            model.showGroundTruthEvidence(truth.caseId, truth.version, source.observationId, source.field, page)
                        } }) { Text("Destacar fonte esperada da diferença — página $page") }
                    } }
                    if (sources.original == null) Text("Sem associação inequívoca com a fonte extraída nesta versão; confira os campos e páginas acima.")
                }
            }
        }
    }
}

internal fun reason(code: ReasonCode) = when (code) {
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
