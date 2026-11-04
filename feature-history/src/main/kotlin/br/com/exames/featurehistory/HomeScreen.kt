package br.com.exames.featurehistory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.exames.coreui.*
import br.com.exames.domain.summary.*
import br.com.exames.domain.validation.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun runStateLabel(state: RunState) = when (state) {
    RunState.QUEUED -> "Aguardando processamento"; RunState.RUNNING -> "Em processamento"
    RunState.COMPLETE -> "Extração concluída"; RunState.PARTIAL -> "Extração parcial"
    RunState.FAILED -> "Extração falhou"; RunState.CANCELLED -> "Extração cancelada"
}
internal fun runStateKind(state: RunState) = if (state == RunState.COMPLETE) ChipKind.INFO else ChipKind.REVIEW
internal fun formatLabel(f: DocumentFormat) = when (f) {
    DocumentFormat.PDF -> "Documento PDF"; DocumentFormat.PNG -> "Imagem PNG"; DocumentFormat.JPEG -> "Imagem JPEG"; DocumentFormat.OTHER -> "Documento"
}
internal fun modeLabel(m: ExtractionMode) = if (m == ExtractionMode.BASELINE) "Sem IA" else "IA local"
internal fun inferenceLabel(o: InferenceOutcome) = when (o) {
    InferenceOutcome.SUCCEEDED -> "IA executada"; InferenceOutcome.FALLBACK -> "Fallback sem IA"
    InferenceOutcome.FAILED -> "IA falhou"; InferenceOutcome.CANCELLED -> "IA cancelada"; InferenceOutcome.NOT_ATTEMPTED -> null
}

@Composable
fun HomeScreen(onSend: () -> Unit, onOpenRun: (String) -> Unit, onExams: () -> Unit, model: HomeViewModel = viewModel()) {
    val ui by model.state.collectAsStateWithLifecycle()
    ExamesScreen("Meu Exame") {
        Text("Leitura referencial de resultados laboratoriais. O processamento acontece neste aparelho.",
            style = MaterialTheme.typography.bodyLarge, color = ExamesTheme.palette.secondaryText)
        PrimaryAction("Enviar exame", onSend)
        Text("Meus exames", style = MaterialTheme.typography.titleLarge, color = ExamesTheme.palette.title)
        when {
            ui.loading -> CircularProgressIndicator()
            ui.home is HomeState.Unavailable -> ExamesCard {
                Text("Não foi possível acessar o armazenamento protegido. Isso não significa que não há exames.")
                SecondaryAction("Tentar novamente", model::reload)
            }
            ui.home is HomeState.Empty -> ExamesCard(Modifier.testTag("home-empty")) {
                Text("Nenhum exame importado ainda.", style = MaterialTheme.typography.titleMedium)
                Text("Envie uma foto, PDF ou imagem para ver a leitura referencial aqui.")
            }
            ui.home is HomeState.Loaded -> (ui.home as HomeState.Loaded).cards.forEach { card ->
                ExamesCard(Modifier.testTag("home-document-${card.documentId}")) {
                    Text(formatLabel(card.format), style = MaterialTheme.typography.titleMedium)
                    Text("Importado em " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(card.importedAt))
                    if (card.extractions.isEmpty()) StatusChip("Sem extração automática", ChipKind.REVIEW)
                    card.extractions.forEach { e ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            StatusChip("${modeLabel(e.mode)}: ${runStateLabel(e.state)}", runStateKind(e.state))
                        }
                        inferenceLabel(e.inferenceOutcome)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        SecondaryAction("Abrir resultado (${modeLabel(e.mode)})", { onOpenRun(e.runId) })
                    }
                    if (card.derivedRunCount > 0) Text("${card.derivedRunCount} execução(ões) corrigida(s) ou reavaliada(s) nos detalhes.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        ExamesTextButton(onClick = onExams, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("Ver todos os exames e detalhes") }
    }
}
