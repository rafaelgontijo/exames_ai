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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Exames Offline", style = MaterialTheme.typography.headlineMedium)
        Text("Leitura referencial de resultados laboratoriais, no seu dispositivo.")
        Text("Não fornece diagnósticos nem recomendações clínicas.")
        Text("PDF, JPEG ou PNG · até 20 MiB e 20 páginas")
        for(mode in ExtractionMode.entries) Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(
            selected=state.mode==mode,enabled=!state.importing && !state.actionBusy && state.modelReadiness!=ModelReadiness.PREPARING,
            role=Role.RadioButton,onClick={ model.selectMode(mode) }),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
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
            else TextButton(onClick=model::recheckModel,enabled=!state.importing && !state.actionBusy) { Text("Preparar/verificar modelo") }
            Text("O modelo propõe campos da fonte; o avaliador determinístico faz a comparação. Falhas usam o caminho sem IA e são identificadas como fallback.")
        }
        val canImport=!state.importing && !state.actionBusy && (state.mode==ExtractionMode.BASELINE || state.modelReadiness==ModelReadiness.READY)
        Button(enabled = canImport, onClick = { picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png")) }) {
            Text("Selecionar documento")
        }
        OutlinedButton(enabled = canImport, onClick = { corpusPicker.launch(arrayOf("application/zip", "application/x-zip-compressed")) }) {
            Text("Importar pacote do corpus")
        }
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
                TextButton(enabled = !state.actionBusy, onClick = model::cancel) { Text("Cancelar processamento") }
                if (status == RunState.QUEUED) TextButton(enabled = !state.actionBusy, onClick = model::reschedule) { Text("Tentar agendamento") }
            }
            if (status in setOf(RunState.PARTIAL, RunState.FAILED, RunState.CANCELLED))
                TextButton(enabled = !state.actionBusy, onClick = model::retry) { Text("Reprocessar em nova execução") }
        }
        state.message?.let { Text(it) }
        state.runId?.let { id -> TextButton(onClick = { onResults(id) }) { Text("Revisar resultados") } }
        TextButton(onClick = onHistory) { Text("Histórico local") }
    }
}
