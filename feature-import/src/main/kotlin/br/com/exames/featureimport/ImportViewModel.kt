package br.com.exames.featureimport

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.exames.domain.model.Document
import br.com.exames.domain.usecase.ManageProcessingRuns
import br.com.exames.domain.usecase.StartProcessingRun
import br.com.exames.domain.validation.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import br.com.exames.domain.inference.*

fun interface DocumentIntake { suspend fun import(uri: Uri): Document }
fun interface CorpusIntake { suspend fun import(uri: Uri, onImported: suspend (Document) -> Unit): Int }
data class ImportUiState(val importing: Boolean = false, val actionBusy: Boolean = false,
    val documentId: String? = null, val runId: String? = null, val runState: RunState? = null,
    val completedPages: Int = 0, val failedPages: Int = 0, val totalPages: Int? = null,
    val importedCases: Int = 0,
    val mode: ExtractionMode = ExtractionMode.BASELINE, val modelReadiness: ModelReadiness = ModelReadiness.NOT_PREPARED,
    val message: String? = null)

@HiltViewModel
class ImportViewModel @Inject constructor(private val intake: DocumentIntake,
    private val starter: StartProcessingRun, private val controls: ManageProcessingRuns,
    private val repository: ValidationRepository, private val corpusIntake: CorpusIntake,
    private val preparation: PrepareLocalInference) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportUiState())
    val state = mutableState.asStateFlow()
    private var monitor: Job? = null
    fun recheckModel() {
        if(state.value.importing || state.value.actionBusy || state.value.modelReadiness==ModelReadiness.PREPARING) return
        mutableState.value=state.value.copy(modelReadiness=ModelReadiness.NOT_PREPARED)
        selectMode(ExtractionMode.ASSISTED)
    }
    fun selectMode(mode: ExtractionMode) {
        if (state.value.importing || state.value.actionBusy || state.value.modelReadiness==ModelReadiness.PREPARING) return
        mutableState.value=state.value.copy(mode=mode,message=null)
        if(mode==ExtractionMode.BASELINE || state.value.modelReadiness==ModelReadiness.READY) return
        mutableState.value=state.value.copy(modelReadiness=ModelReadiness.PREPARING)
        viewModelScope.launch {
            try { mutableState.value=state.value.copy(modelReadiness=preparation.prepare()) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value=state.value.copy(modelReadiness=ModelReadiness.STORAGE_UNAVAILABLE) }
        }
    }
    private fun ready() = state.value.mode==ExtractionMode.BASELINE || state.value.modelReadiness==ModelReadiness.READY
    private fun beginImport() { mutableState.value=ImportUiState(importing=true,mode=state.value.mode,modelReadiness=state.value.modelReadiness) }
    fun selectPackage(uri: Uri) {
        if (state.value.importing || state.value.actionBusy || !ready()) return
        monitor?.cancel()
        beginImport()
        viewModelScope.launch {
            try {
                corpusIntake.import(uri) { document ->
                    val started = starter.start(document.id, UUID.randomUUID().toString(), state.value.mode)
                    mutableState.value = state.value.copy(documentId = document.id, runId = started.run.runId,
                        importedCases = state.value.importedCases + 1)
                }
                val current = state.value
                watch(requireNotNull(current.documentId), requireNotNull(current.runId),
                    "Pacote salvo. Acompanhe cada caso e sua execução no histórico; abaixo aparece o último caso.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(importing = false,
                message = "Pacote inválido, acima dos limites ou importação interrompida. Casos já salvos: ${state.value.importedCases}. Confira o histórico antes de importar novamente.") }
        }
    }
    fun select(uri: Uri) {
        if (state.value.importing || state.value.actionBusy || !ready()) return
        monitor?.cancel()
        beginImport()
        viewModelScope.launch {
            try {
                val document = intake.import(uri)
                mutableState.value = state.value.copy(documentId = document.id)
                val started = starter.start(document.id, UUID.randomUUID().toString(), state.value.mode)
                watch(document.id, started.run.runId, if (started.scheduled) null else
                    "Documento salvo. O agendamento está pendente; você pode tentar novamente sem importar outra cópia.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = state.value.copy(importing = false, message = if (state.value.documentId != null)
                    "Documento salvo. Não foi possível iniciar o processamento; acesse-o no histórico."
                    else "Não foi possível importar. Use PDF, JPEG ou PNG de até 20 MiB e confira o acesso ao armazenamento protegido.")
            }
        }
    }

    private fun watch(documentId: String, runId: String, message: String? = null) {
        monitor?.cancel()
        mutableState.value = state.value.copy(importing = false, documentId = documentId, runId = runId,
            runState = RunState.QUEUED, completedPages = 0, failedPages = 0, totalPages = null, message = message)
        monitor = viewModelScope.launch {
            try {
                combine(repository.observeRuns(documentId), repository.observeCheckpoints(runId)) { runs, checkpoints ->
                    runs.singleOrNull { it.runId == runId } to checkpoints
                }.collect { (run, checkpoints) ->
                    if (run == null) mutableState.value = ImportUiState(message = "Documento indisponível ou removido do histórico.")
                    else mutableState.value = state.value.copy(runState = run.state,
                        completedPages = checkpoints.count { it.state == PageState.COMPLETE },
                        failedPages = checkpoints.count { it.state == PageState.FAILED },
                        totalPages = repository.find(documentId)?.pageCount,
                        message = if (run.state == RunState.QUEUED) state.value.message else null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = ImportUiState(message = "Não foi possível acessar o armazenamento protegido.") }
        }
    }

    fun cancel() = action { state.value.runId?.let { controls.cancel(it) }; Unit }
    fun reschedule() = action { controls.reconcile(); mutableState.value = state.value.copy(message = null) }
    fun retry() = action {
        val parentId = state.value.runId ?: return@action
        val id = UUID.randomUUID().toString()
        var pending = false
        val child = try { controls.retry(parentId, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { pending = true; repository.findRun(id) ?: throw error }
        watch(child.documentId, child.runId, if (pending) "Nova execução salva; agendamento pendente." else null)
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.importing || state.value.actionBusy) return
        mutableState.value = state.value.copy(actionBusy = true)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(message = "Não foi possível concluir a ação. O histórico salvo foi preservado.") }
            finally { mutableState.value = state.value.copy(actionBusy = false) }
        }
    }
}
