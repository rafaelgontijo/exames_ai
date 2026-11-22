package br.com.exames.featureimport

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.exames.datadocuments.CaptureRejected
import br.com.exames.datadocuments.CapturedPage
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
/** Confirmed camera pages go through the protected importer; the page is wiped by the caller afterwards. */
fun interface CaptureIntake { suspend fun import(page: CapturedPage): Document }
/** Bound by the capture screen while its camera is open and detached when that screen leaves. */
fun interface PageCaptureSource { suspend fun capture(): CapturedPage }
enum class CapturePhase { NONE, CAMERA, REVIEW }
fun interface CorpusIntake { suspend fun import(uri: Uri, onImported: suspend (Document) -> Unit): Int }
data class ImportUiState(val importing: Boolean = false, val actionBusy: Boolean = false,
    val documentId: String? = null, val runId: String? = null, val runState: RunState? = null,
    val completedPages: Int = 0, val failedPages: Int = 0, val totalPages: Int? = null,
    val importedCases: Int = 0,
    val mode: ExtractionMode = ExtractionMode.BASELINE, val modelReadiness: ModelReadiness = ModelReadiness.NOT_PREPARED,
    /** Camera flow. The captured image itself is never part of this state; see [ImportViewModel.previewBytes]. */
    val capture: CapturePhase = CapturePhase.NONE, val captureRevision: Int = 0,
    val message: String? = null)

@HiltViewModel
class ImportViewModel @Inject constructor(private val intake: DocumentIntake,
    private val starter: StartProcessingRun, private val controls: ManageProcessingRuns,
    private val repository: ValidationRepository, private val corpusIntake: CorpusIntake,
    private val preparation: PrepareLocalInference, private val captureIntake: CaptureIntake) : ViewModel() {
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
                processImported(intake.import(uri))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = state.value.copy(importing = false, message = if (state.value.documentId != null)
                    "Documento salvo. Não foi possível iniciar o processamento; acesse-o no histórico."
                    else "Não foi possível importar. Use PDF, JPEG ou PNG de até 20 MiB e confira o acesso ao armazenamento protegido.")
            }
        }
    }

    private suspend fun processImported(document: Document) {
        mutableState.value = state.value.copy(documentId = document.id)
        val started = starter.start(document.id, UUID.randomUUID().toString(), state.value.mode)
        watch(document.id, started.run.runId, if (started.scheduled) null else
            "Documento salvo. O agendamento está pendente; você pode tentar novamente sem importar outra cópia.")
    }

    // ---- Camera capture. The candidate lives only in memory; every transition bumps the session so late results are dropped.
    private var captureSession = 0L
    private var candidate: CapturedPage? = null
    private var captureSource: PageCaptureSource? = null
    private var captureJob: Job? = null

    /** Permission result from the system dialog. Denial creates nothing and leaves the file picker available. */
    fun onCameraPermission(granted: Boolean) {
        if (granted) beginCapture()
        else endCapture("A câmera não foi autorizada. Nenhum documento foi criado; você ainda pode selecionar um PDF ou imagem.")
    }
    fun beginCapture() {
        if (state.value.importing || state.value.actionBusy || !ready()) return
        discardCandidate(); captureJob?.cancel()
        captureSession++
        mutableState.value = state.value.copy(capture = CapturePhase.CAMERA, message = null)
    }
    fun attachCaptureSource(source: PageCaptureSource) { captureSource = source }
    fun detachCaptureSource(source: PageCaptureSource) { if (captureSource === source) captureSource = null }
    fun onCameraFailure(code: String) = endCapture(captureMessage(code))

    fun takePhoto() {
        if (state.value.capture != CapturePhase.CAMERA || captureJob?.isActive == true) return
        val source = captureSource ?: run { mutableState.value = state.value.copy(message = captureMessage("CAMERA_UNAVAILABLE")); return }
        val session = captureSession
        captureJob = viewModelScope.launch {
            try {
                val page = source.capture()
                if (session != captureSession || state.value.capture != CapturePhase.CAMERA) { page.wipe(); return@launch }
                candidate = page
                mutableState.value = state.value.copy(capture = CapturePhase.REVIEW, captureRevision = state.value.captureRevision + 1, message = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (rejected: CaptureRejected) { if (session == captureSession) mutableState.value = state.value.copy(message = captureMessage(rejected.code)) }
            catch (_: Exception) { if (session == captureSession) mutableState.value = state.value.copy(message = captureMessage("CAPTURE_DECODE_FAILED")) }
        }
    }
    /** Copy for drawing the review image; the caller wipes it. Null when there is no candidate. */
    fun previewBytes(): ByteArray? = candidate?.jpeg?.copyOf()
    fun retake() {
        if (state.value.capture != CapturePhase.REVIEW) return
        discardCandidate(); captureSession++
        mutableState.value = state.value.copy(capture = CapturePhase.CAMERA, captureRevision = state.value.captureRevision + 1, message = null)
    }
    fun cancelCapture() = endCapture(null)
    /** Leaving the screen, losing permission, cancelling or ending the session: discard and invalidate callbacks. */
    fun endCapture(message: String? = null) {
        val active = state.value.capture != CapturePhase.NONE
        captureSession++; captureJob?.cancel(); captureJob = null; discardCandidate()
        if (active || message != null) mutableState.value = state.value.copy(capture = CapturePhase.NONE, message = message)
    }
    /** Confirming selects this image as the source; it does not confirm any extracted value. */
    fun useCapture() {
        if (state.value.capture != CapturePhase.REVIEW || state.value.importing || state.value.actionBusy) return
        val page = candidate ?: return
        candidate = null; captureSession++; captureJob?.cancel()
        monitor?.cancel()
        beginImport()
        viewModelScope.launch {
            try { processImported(captureIntake.import(page)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = state.value.copy(importing = false, message = if (state.value.documentId != null)
                    "Foto salva. Não foi possível iniciar o processamento; acesse-a no histórico."
                    else "Não foi possível salvar a foto. Nada foi criado; refaça a foto ou use o seletor de arquivos.")
            } finally { page.wipe() }
        }
    }
    private fun discardCandidate() { candidate?.wipe(); candidate = null }
    private fun captureMessage(code: String) = when (code) {
        "CAMERA_UNAVAILABLE", "CAMERA_NOT_OPEN" -> "Câmera indisponível. Use o seletor de arquivos."
        "CAPTURE_TOO_LARGE" -> "A foto excede o limite de 20 MiB. Tente novamente ou use o seletor."
        "CAPTURE_ORIENTATION_INCONSISTENT" -> "Não foi possível confirmar a orientação da foto. Tente novamente."
        else -> "Não foi possível usar esta foto. Tente de novo ou use o seletor de arquivos."
    }
    override fun onCleared() { captureSession++; captureJob?.cancel(); discardCandidate(); super.onCleared() }

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
