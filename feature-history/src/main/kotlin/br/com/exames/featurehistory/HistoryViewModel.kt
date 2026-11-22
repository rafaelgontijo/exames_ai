package br.com.exames.featurehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.exames.domain.model.Document
import br.com.exames.domain.usecase.StartProcessingRun
import br.com.exames.domain.validation.ProcessingRun
import br.com.exames.domain.validation.ValidationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class HistoryUiState(val documents: List<Document> = emptyList(), val selectedDocumentId: String? = null,
    val runs: List<ProcessingRun> = emptyList(), val loading: Boolean = true, val loadingRuns: Boolean = false,
    val actionBusy: Boolean = false, val unavailable: Boolean = false, val message: String? = null)

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repository: ValidationRepository,
    private val starter: StartProcessingRun, private val preparation: br.com.exames.domain.inference.PrepareLocalInference) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryUiState())
    val state = mutableState.asStateFlow()
    private var runMonitor: Job? = null
    private var documentMonitor: Job? = null
    init { reload() }
    fun reload() {
        documentMonitor?.cancel(); runMonitor?.cancel()
        mutableState.value = HistoryUiState()
        documentMonitor = viewModelScope.launch {
            try {
                repository.observeDocuments().collect { documents ->
                    val selected = state.value.selectedDocumentId?.takeIf { id -> documents.any { it.id == id } }
                    if (selected == null) runMonitor?.cancel()
                    mutableState.value = state.value.copy(documents = documents, selectedDocumentId = selected,
                        runs = if (selected == null) emptyList() else state.value.runs,
                        loading = false, loadingRuns = selected != null && state.value.loadingRuns, unavailable = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                runMonitor?.cancel()
                mutableState.value = HistoryUiState(loading = false, unavailable = true,
                    message = "Não foi possível acessar o armazenamento protegido.")
            }
        }
    }

    fun selectDocument(id: String) {
        if (state.value.documents.none { it.id == id }) return
        runMonitor?.cancel()
        mutableState.value = state.value.copy(selectedDocumentId = id, runs = emptyList(), loadingRuns = true, message = null)
        runMonitor = viewModelScope.launch {
            try {
                repository.observeRuns(id).collect { runs -> mutableState.value = state.value.copy(runs = runs, loadingRuns = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(runs = emptyList(), loadingRuns = false,
                message = "Não foi possível acessar as execuções salvas.") }
        }
    }
    fun closeDocument() {
        runMonitor?.cancel()
        mutableState.value = state.value.copy(selectedDocumentId = null, runs = emptyList(), loadingRuns = false, message = null)
    }
    fun deleteDocument(id: String) = action {
        repository.deleteCompletely(id)
        mutableState.value = state.value.copy(documents = state.value.documents.filterNot { it.id == id },
            message = "Documento removido do histórico. A limpeza privada é retomada automaticamente se necessário.")
        if (state.value.selectedDocumentId == id) closeDocument()
    }
    fun processDocument(id: String) = action {
        val started = starter.start(id, UUID.randomUUID().toString())
        mutableState.value = state.value.copy(message = if (started.scheduled) "Execução sem IA criada."
            else "Execução salva; agendamento pendente. O documento não precisa ser importado novamente.")
    }
    fun processAssisted(id: String) = action {
        mutableState.value=state.value.copy(message="Preparando e verificando o modelo local…")
        if(preparation.prepare()!=br.com.exames.domain.inference.ModelReadiness.READY) {
            mutableState.value=state.value.copy(message="Modo assistido indisponível neste aparelho ou modelo não preparado. A execução sem IA continua disponível.")
            return@action
        }
        val started=starter.start(id,UUID.randomUUID().toString(),br.com.exames.domain.validation.ExtractionMode.ASSISTED)
        mutableState.value=state.value.copy(message=if(started.scheduled) "Execução assistida criada; confira uso real ou fallback nos resultados."
            else "Execução assistida salva; agendamento pendente.")
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.actionBusy) return
        mutableState.value = state.value.copy(actionBusy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(message = "Não foi possível concluir a ação no armazenamento protegido.") }
            finally { mutableState.value = state.value.copy(actionBusy = false) }
        }
    }
}
