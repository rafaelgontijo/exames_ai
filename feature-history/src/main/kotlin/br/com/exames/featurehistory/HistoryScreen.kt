package br.com.exames.featurehistory

import br.com.exames.coreui.ExamesTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.exames.domain.validation.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(onBack: () -> Unit, onRun: (String) -> Unit, model: HistoryViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    HistoryContent(state, onBack, onRun, model::reload, model::selectDocument, model::closeDocument, model::processDocument,
        model::processAssisted, model::deleteDocument)
}

/** Stateless so deletion, new runs and run access can be tested without storage. */
@Composable
fun HistoryContent(state: HistoryUiState, onBack: () -> Unit, onRun: (String) -> Unit, onReload: () -> Unit,
    onSelectDocument: (String) -> Unit, onCloseDocument: () -> Unit, onProcess: (String) -> Unit,
    onProcessAssisted: (String) -> Unit, onDelete: (String) -> Unit) {
    var deleting by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(24.dp).testTag("history-list"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Histórico local", style = MaterialTheme.typography.headlineMedium) }
        item { ExamesTextButton(onClick = onBack) { Text("Voltar") } }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.unavailable) item { ExamesTextButton(onClick = onReload) { Text("Tentar abrir histórico novamente") } }
        state.message?.let { message -> item { Text(message) } }
        if (!state.loading && !state.unavailable && state.documents.isEmpty()) item { Text("Nenhum documento salvo.") }
        if (state.selectedDocumentId == null) {
            items(state.documents, key = { it.id }) { document ->
                Card(Modifier.fillMaxWidth().testTag("document-${document.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when (document.mimeType) { "application/pdf" -> "Documento PDF"; "image/png" -> "Imagem PNG"; else -> "Imagem JPEG" },
                            style = MaterialTheme.typography.titleMedium)
                        Text("Importado em " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(document.importedAt))
                        ExamesTextButton(onClick = { onSelectDocument(document.id) }) { Text("Abrir execuções") }
                        ExamesTextButton(enabled = !state.actionBusy, onClick = { deleting = document.id }) { Text("Excluir documento") }
                    }
                }
            }
        } else {
            item {
                ExamesTextButton(onClick = onCloseDocument) { Text("Todos os documentos") }
                if (state.loadingRuns) CircularProgressIndicator()
                else if (state.runs.isEmpty()) Text("Documento salvo, sem execuções.")
                ExamesTextButton(enabled = !state.actionBusy, onClick = { state.selectedDocumentId?.let(onProcess) }) { Text("Nova execução sem IA") }
                ExamesTextButton(enabled = !state.actionBusy, onClick = { state.selectedDocumentId?.let(onProcessAssisted) }) { Text("Nova execução assistida") }
                ExamesTextButton(enabled = !state.actionBusy, onClick = { deleting = state.selectedDocumentId }) { Text("Excluir documento e derivados") }
            }
            items(state.runs.reversed(), key = { it.runId }) { run ->
                Card(Modifier.fillMaxWidth().testTag("run-${run.runId}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (run.mode == ExtractionMode.BASELINE) "Execução sem IA" else "Execução assistida")
                        Text(when (run.outputBasis) {
                            br.com.exames.domain.validation.OutputBasis.AUTOMATIC -> "Extração automática"
                            br.com.exames.domain.validation.OutputBasis.CORRECTED -> "Reavaliação após correções; sem nova extração"
                            br.com.exames.domain.validation.OutputBasis.REASSESSED -> "Reavaliação da regra; sem nova extração"
                        })
                        Text(when (run.state) {
                            RunState.QUEUED -> "Aguardando processamento"; RunState.RUNNING -> "Em processamento"
                            RunState.COMPLETE -> "Concluída"; RunState.PARTIAL -> "Parcial — há páginas com falha"
                            RunState.FAILED -> "Falhou"; RunState.CANCELLED -> "Cancelada"
                        })
                        if (run.parentRunId != null) Text("Nova execução vinculada à anterior")
                        ExamesTextButton(onClick = { onRun(run.runId) }) { Text("Abrir resultado e fonte") }
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Excluir documento?") },
            text = { Text("O original privado, as execuções, correções, gabaritos associados e outros derivados serão removidos. Esta ação não pode ser desfeita.") },
            confirmButton = { ExamesTextButton(onClick = { deleting = null; onDelete(id) }) { Text("Excluir definitivamente") } },
            dismissButton = { ExamesTextButton(onClick = { deleting = null }) { Text("Manter documento") } })
    }
}
