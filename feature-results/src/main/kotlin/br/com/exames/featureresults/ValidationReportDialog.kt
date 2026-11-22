package br.com.exames.featureresults

import br.com.exames.coreui.ExamesTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.exames.domain.validation.*

private fun fraction(f: ReportFraction) = if(f.denominator==0L) "N/A — sem denominador" else "${f.numerator}/${f.denominator}"
@Composable
internal fun ValidationReportDialog(report: LocalValidationReport, pair: PairedValidationReport?, run: ProcessingRun?,
    pages: List<PageCheckpoint>, close: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    var strata by remember { mutableStateOf(false) }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier=Modifier.fillMaxWidth(.95f).fillMaxHeight(.95f),shape=MaterialTheme.shapes.large) {
            LazyColumn(Modifier.padding(16.dp).testTag("validation-report"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Text("Relatório local",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics { heading() })
                    ExamesTextButton(onClick=close) { Text("Fechar relatório") }
                    Text("Resultado técnico; sem validação clínica.")
                    if(!report.publicCoverageAvailable) Text("Sem cobertura pública revisada neste relatório.") }
                pair?.let { p -> item {
                    Text("Comparação sem IA / assistido",style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() })
                    Text("Diferenças: ${p.divergences.size}. Novos erros em casos antes corretos: ${p.newErrors.size}.")
                    Text("Novos resultados fora da referência mostrados como dentro: ${p.newAbnormalShownWithin.size}.")
                    Text("Variação na quantidade classificada numericamente: ${p.numericCoverageDelta}. Cobertura maior não comprova melhoria.")
                    Text("Páginas com proposta de IA aceita: ${p.assisted.acceptedModelPages}. Com fallback/falha: ${p.assisted.fallbackOrFailedPages}.")
                } }
                items(report.rows.filter { strata || it.dimension==ReportDimension.ALL }) { row ->
                    val c=row.counts
                    HorizontalDivider()
                    Text("${if(row.dimension==ReportDimension.ALL) "Total da seleção" else "${row.dimension}: ${row.value}"} · ${if(row.cohort.mode==ExtractionMode.BASELINE) "Sem IA" else "Assistido"}",style=MaterialTheme.typography.titleSmall)
                    Text("Casos ${c.samples}; famílias ${c.families}; pendentes ${c.pendingSamples}; incompletos ${c.incompleteSamples}.")
                    Text("Acerto ponta a ponta: ${fraction(c.classificationAccuracy)}. Cobertura numérica: ${fraction(c.numericCoverage)}.")
                    Text("Acerto bruto: ${fraction(c.rawAccuracy)}. Canônico: ${fraction(c.canonicalAccuracy)}.")
                    Text("Avaliador isolado — estado: ${fraction(c.isolatedStatusAccuracy)}; motivo: ${fraction(c.isolatedReasonAccuracy)}.")
                    Text("Ausentes ${c.missing}; extras ${c.extra}; associações não resolvidas ${c.unresolvedExpected}.")
                    Text("Estados pareados: ${statusCounts(c.matchedStatusCounts)}. Extras: ${statusCounts(c.extraStatusCounts)}.")
                    Text("Anormais: ${c.abnormalCounts}. Isolado indisponível: ${c.isolatedUnavailable}.")
                }
                item {
                    ExamesTextButton(onClick={ strata=!strata }) { Text(if(strata) "Recolher estratos" else "Detalhar origem, formato, analito, layout e partição") }
                    ExamesTextButton(onClick={ details=!details }) { Text(if(details) "Recolher identidade e métodos" else "Identidade, versões e métodos de medição") }
                }
                if(details) {
                items(report.identities) { identity ->
                    Text("Caso ${identity.caseId} · família ${identity.familyId}")
                    Text("Execução ${identity.runId} · ${identity.cohort.mode} · ${identity.cohort.basis}")
                    Text("Gabarito ${identity.truthVersion} · ${if(identity.reviewed) "Revisado" else "Pendente, fora dos denominadores"}")
                    Text("Aparelho ${identity.deviceModel} · Android ${identity.androidVersion} · regra ${identity.cohort.ruleVersion}")
                    identity.cohort.inference?.let { config ->
                        Text("Modelo ${config.modelId} · backend ${config.backend} · runtime ${config.runtimeVersion}")
                        Text("Revisão ${config.modelRevision}; SHA-256 ${config.modelSha256}")
                        Text("Prompt ${config.promptVersion}; schema ${config.schemaVersion}; contexto/saída ${config.maxContextTokens}/${config.maxGenerationTokens} tokens.")
                    }
                }
                item { Text("Tempo e memória",style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() })
                    Text("Tempo monotônico por página. PSS do app em duas amostras; PSS da inferência a cada 100 ms. São amostras, não pico garantido; memória GPU pode estar incompleta.")
                    Text("Engine/processo novos em cada solicitação. Cache do sistema não controlado; não há medição de reutilização quente da Engine.")
                    Text("O runtime informa tokens de prefill/decode totais. Contagens separadas de pensamento/final: N/A; a API não as fornece. Pensamento não é exibido ou armazenado.") }
                val performance=pair?.let { listOf(it.baseline,it.assisted) } ?: run?.let { listOf(RunPerformance(it.runId,it.deviceModel,it.androidVersion,it.inferenceOutcome,pages,0,0)) }.orEmpty()
                for (p in performance) {
                    item { Text("Execução ${p.runId}: ${p.outcome}") }
                    items(p.pages) { page ->
                        Text("Página ${page.page}: ${page.state}; duração ${page.durationMillis?.let { "$it ms" } ?: "N/A"}; ${page.errorCode ?: "sem falha registrada"}.")
                        page.measurements?.let { m -> Text("PSS app antes/depois: ${m.appPssBeforeKiB}/${m.appPssAfterKiB} KiB. RAM total: ${m.totalRamBytes} bytes. Disponível antes/depois: ${m.availableBytesBefore}/${m.availableBytesAfter} bytes.") }
                            ?: Text("Medições de memória do app: N/A nesta execução.")
                        val a=page.inferenceAttempt
                        if(a!=null) {
                            Text("Inferência: ${a.disposition}; ${a.failure ?: "sem falha de Engine registrada"}. Carga fria ${a.loadMillis?.toString() ?: "N/A"} ms; resposta final ${a.generationMillis?.toString() ?: "N/A"} ms.")
                            a.measurements?.let { m -> Text("Maior PSS amostrada da inferência: ${m.sampledMaxPssKiB} KiB. Prefill/decode/contexto: ${m.prefillTokens ?: "N/A"}/${m.decodedTokens ?: "N/A"}/${m.contextTokensAfter ?: "N/A"} tokens.") }
                                ?: Text("Medições da inferência: N/A — não disponíveis nesta tentativa.")
                        }
                    }
                }
                }
            }
        }
    }
}

private fun statusCounts(values: Map<String,Int>) = if(values.isEmpty()) "sem observações" else values.entries.joinToString("; ") {
    val label=when(it.key) {
        "NEEDS_REVIEW" -> "Precisa de revisão";"NOT_CLASSIFIABLE" -> "Não classificável"
        "WITHIN_RANGE" -> "Dentro da referência";"BELOW_RANGE" -> "Abaixo da referência";"ABOVE_RANGE" -> "Acima da referência"
        else -> it.key
    }
    "$label ${it.value}"
}
