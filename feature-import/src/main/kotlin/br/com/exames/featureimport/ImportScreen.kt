package br.com.exames.featureimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import br.com.exames.coreui.*
import br.com.exames.domain.inference.ModelReadiness
import br.com.exames.domain.validation.ExtractionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.exames.domain.validation.RunState

@Composable
fun ImportScreen(onResults: (String) -> Unit, onHistory: () -> Unit, model: ImportViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::select)
    }
    val corpusPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::selectPackage) }
    ImportContent(state, onPickDocument = { picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png")) },
        onPickPackage = { corpusPicker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
        onSelectMode = model::selectMode, onRecheckModel = model::recheckModel, onCancel = model::cancel,
        onReschedule = model::reschedule, onRetry = model::retry, onResults = onResults, onHistory = onHistory)
}

/** Stateless so the controls can be exercised without a ViewModel, storage or the model. */
@Composable
fun ImportContent(state: ImportUiState, onPickDocument: () -> Unit, onPickPackage: () -> Unit, onSelectMode: (ExtractionMode) -> Unit,
    onRecheckModel: () -> Unit, onCancel: () -> Unit, onReschedule: () -> Unit, onRetry: () -> Unit,
    onResults: (String) -> Unit, onHistory: () -> Unit) {
    ExamesScreen("Enviar exame") {
        ExamesCard {
            Text("Leitura referencial de resultados laboratoriais, no seu dispositivo.", style = MaterialTheme.typography.titleMedium)
            Text("O processamento acontece neste aparelho; nada é enviado para fora. Escolher uma fonte não confirma os valores lidos.")
            Text("Não fornece diagnósticos nem recomendações clínicas.")
            Text("PDF, JPEG ou PNG · até 20 MiB e 20 páginas")
        }
        Text("Como processar?", style = MaterialTheme.typography.titleLarge, color = ExamesTheme.palette.title)
        for(mode in ExtractionMode.entries) Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(
            selected=state.mode==mode,enabled=!state.importing && !state.actionBusy && state.modelReadiness!=ModelReadiness.PREPARING,
            role=Role.RadioButton,onClick={ onSelectMode(mode) }),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            RadioButton(selected=state.mode==mode,onClick=null)
            Text(if(mode==ExtractionMode.BASELINE) "Modo sem IA" else "Modo assistido local")
        }
        if(state.mode==ExtractionMode.ASSISTED) {
            Text(when(state.modelReadiness) {
                ModelReadiness.NOT_PREPARED -> "Prepare e verifique o modelo antes de importar."
                ModelReadiness.PREPARING -> "Preparando e verificando o modelo local; pode levar alguns instantes."
                ModelReadiness.READY -> "Modelo preparado nesta sessão. A integridade é verificada novamente em cada inferência."
                ModelReadiness.UNSUPPORTED_DEVICE -> "Modo assistido validado somente no S25 de referência desta versão. O modo sem IA está disponível neste aparelho."
                ModelReadiness.VERIFICATION_PENDING -> "Modelo íntegro preparado; a ativação aguarda o ensaio final do protocolo."
                ModelReadiness.MISSING -> "O modelo não está disponível nesta distribuição. Nenhum download será feito."
                ModelReadiness.CORRUPT -> "A verificação de integridade do modelo falhou."
                ModelReadiness.INSUFFICIENT_SPACE -> "Espaço insuficiente para preparar o modelo. Libere espaço ou use o modo sem IA."
                ModelReadiness.STORAGE_UNAVAILABLE -> "O armazenamento do modelo está indisponível."
            })
            if(state.modelReadiness==ModelReadiness.PREPARING) CircularProgressIndicator()
            else ExamesTextButton(onClick=onRecheckModel,enabled=!state.importing && !state.actionBusy) { Text("Preparar/verificar modelo") }
            Text("O modelo propõe campos da fonte; o avaliador determinístico faz a comparação. Falhas usam o caminho sem IA e são identificadas como fallback.")
        }
        val canImport=!state.importing && !state.actionBusy && (state.mode==ExtractionMode.BASELINE || state.modelReadiness==ModelReadiness.READY)
        PrimaryAction("Selecionar documento", onPickDocument, enabled = canImport)
        SecondaryAction("Importar pacote do corpus", onPickPackage, enabled = canImport)
        Text("Pacote ZIP local: até 128 MiB, manifesto, documentos e gabaritos. Hashes verificam integridade, sem autenticar o autor.")
        if (state.importedCases > 0) Text("Casos importados: ${state.importedCases}. Execuções individuais no histórico.")
        if (state.importing) { CircularProgressIndicator(); Text("Criando cópia privada protegida…") }
        state.runState?.let { status ->
            Text(when (status) {
                RunState.QUEUED -> "Aguardando processamento"
                RunState.RUNNING -> "Processando documento"
                RunState.COMPLETE -> "Processamento concluído"
                RunState.PARTIAL -> "Processamento parcial: há páginas com falha"
                RunState.FAILED -> "Não foi possível processar o documento"
                RunState.CANCELLED -> "Processamento cancelado"
            }, style = MaterialTheme.typography.titleMedium)
            Text("Páginas concluídas: ${state.completedPages} de ${state.totalPages ?: "total ainda desconhecido"}. Falhas: ${state.failedPages}.")
            if (status in setOf(RunState.QUEUED, RunState.RUNNING)) {
                if (status == RunState.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                ExamesTextButton(enabled = !state.actionBusy, onClick = onCancel) { Text("Cancelar processamento") }
                if (status == RunState.QUEUED) ExamesTextButton(enabled = !state.actionBusy, onClick = onReschedule) { Text("Tentar agendamento") }
            }
            if (status in setOf(RunState.PARTIAL, RunState.FAILED, RunState.CANCELLED))
                ExamesTextButton(enabled = !state.actionBusy, onClick = onRetry) { Text("Reprocessar em nova execução") }
        }
        state.message?.let { Text(it) }
        state.runId?.let { id -> ExamesTextButton(onClick = { onResults(id) }) { Text("Revisar resultados") } }
        ExamesTextButton(onClick = onHistory, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("Histórico local") }
    }
}
