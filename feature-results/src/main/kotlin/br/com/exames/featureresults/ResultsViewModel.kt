package br.com.exames.featureresults

import android.graphics.Bitmap
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.exames.domain.model.Assessment
import br.com.exames.domain.model.Observation
import br.com.exames.domain.model.ManualCorrection
import br.com.exames.domain.model.ReferenceRange
import br.com.exames.domain.model.CorrectionField
import br.com.exames.domain.usecase.correctionValue
import kotlinx.serialization.encodeToString
import java.util.UUID
import br.com.exames.domain.validation.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

fun interface SourcePreviewLoader { suspend fun load(documentId: String, page: Int): Bitmap }
data class ResultRow(val observation: Observation, val assessment: Assessment?, val corrections: List<ManualCorrection> = emptyList(),
    val reference: ReferenceRange? = null)
data class ResultsUiState(val loading: Boolean = false, val unavailable: Boolean = false,
    val run: ProcessingRun? = null, val rows: List<ResultRow> = emptyList(),
    val checkpoints: List<PageCheckpoint> = emptyList(), val pageCount: Int? = null,
    val groundTruth: List<GroundTruthSnapshot> = emptyList(), val comparisons: List<Comparison> = emptyList(),
    val preview: Bitmap? = null, val previewPage: Int? = null, val previewBusy: Boolean = false,
    val selectedEvidence: SelectedEvidence? = null,
    val draft: ReviewDraft? = null, val reviewBusy: Boolean = false,
    val corpusCase: CorpusCase? = null, val relatedRuns: List<ProcessingRun> = emptyList(),
    val baselineId: String? = null, val assistedId: String? = null,
    val report: LocalValidationReport? = null, val pairedReport: PairedValidationReport? = null,
    val message: String? = null)

@HiltViewModel
class ResultsViewModel @Inject constructor(private val repository: ValidationRepository,
    private val previewLoader: SourcePreviewLoader, private val actions: ReviewActions) : ViewModel() {
    private val mutableState = MutableStateFlow(ResultsUiState())
    val state = mutableState.asStateFlow()
    private var monitor: Job? = null
    private var previewJob: Job? = null
    private var selectedRunId: String? = null
    private var previewGeneration = 0L
    private var reportGeneration = 0L

    fun open(runId: String) {
        reportGeneration++
        monitor?.cancel(); previewJob?.cancel()
        previewGeneration++
        selectedRunId = runId
        mutableState.value = ResultsUiState(loading = true)
        if (!ProcessingRunIds.isOpaque(runId)) { unavailable(); return }
        monitor = viewModelScope.launch {
            try {
                val source = repository.findRun(runId) ?: run { unavailable(); return@launch }
                combine(repository.observeRuns(source.documentId), repository.observeCheckpoints(runId)) { runs, checkpoints ->
                    Triple(runs.singleOrNull { it.runId == runId }, checkpoints, runs)
                }.collect { (run, checkpoints, runs) ->
                    val document = if (run == null) null else repository.find(run.documentId)
                    if (run == null || document == null) unavailable()
                    else {
                        val assessments = repository.assessments(runId).associateBy { it.observationId }
                        val rows = repository.observations(runId).map { ResultRow(it, assessments[it.id], repository.corrections(it.id), repository.reference(it.id)) }
                        mutableState.value = state.value.copy(loading = false, unavailable = false, run = run, rows = rows,
                            checkpoints = checkpoints, pageCount = document.pageCount,
                            corpusCase = document.corpusCase, relatedRuns = runs,
                            groundTruth = repository.groundTruth(run.documentId), comparisons = repository.comparisons(runId))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unavailable("Não foi possível acessar a execução no armazenamento protegido.") }
        }
    }
    fun showPage(page: Int) = requestPreview(page, null)

    fun editCorrection(observationId: String, field: CorrectionField) {
        val run = state.value.run ?: return
        if (state.value.reviewBusy || run.state in setOf(RunState.QUEUED, RunState.RUNNING)) return
        val row = state.value.rows.singleOrNull { it.observation.id == observationId } ?: return
        val prior = row.corrections.lastOrNull { it.field == field }
        val old = if (prior != null) prior.newValue else correctionValue(row.observation, row.reference, field)
        mutableState.value = state.value.copy(draft = ReviewDraft.Correction(observationId, field, old,
            UUID.randomUUID().toString(), TextFieldValue(old.orEmpty())), message = null)
    }
    fun editGroundTruth(hash: String) {
        if (state.value.reviewBusy) return
        val snapshot = state.value.groundTruth.singleOrNull { it.sha256 == hash } ?: return
        val draft = snapshot.groundTruth.copy(version = "revisao-${UUID.randomUUID()}", reviewState = ReviewState.PENDING,
            reviewerId = null, reviewedAt = null)
        mutableState.value = state.value.copy(draft = ReviewDraft.Truth(hash, TextFieldValue(ValidationJson.format.encodeToString(draft))), message = null)
    }
    fun updateDraft(text: TextFieldValue) {
        if (state.value.reviewBusy) return
        val draft = when (val current = state.value.draft) {
            is ReviewDraft.Correction -> if (text.text.length <= 4096) current.copy(text = text) else return
            is ReviewDraft.Truth -> if (text.text.length <= br.com.exames.domain.usecase.GroundTruthDraft.MAX_BYTES) current.copy(text = text) else return
            null -> return
        }
        mutableState.value = state.value.copy(draft = draft)
    }
    fun dismissDraft() { if (!state.value.reviewBusy) mutableState.value = state.value.copy(draft = null) }
    fun saveDraft() {
        val draft = state.value.draft ?: return
        performReview { run ->
            when (draft) {
                is ReviewDraft.Correction -> actions.correct(run.runId, draft.observationId, draft.field, draft.old,
                    draft.text.text.takeUnless { it.isBlank() }, draft.id)
                is ReviewDraft.Truth -> actions.revise(run.documentId, draft.parentHash, draft.text.text)
            }
            run.runId
        }
    }
    fun reevaluate() = performReview { actions.reevaluate(it.runId, UUID.randomUUID().toString()).runId }
    fun reextract() = performReview { actions.reextract(it.documentId, it.runId, UUID.randomUUID().toString()).runId }
    fun compare(hash: String) {
        val truth = state.value.groundTruth.singleOrNull { it.sha256 == hash } ?: return
        if (!truth.groundTruth.hasReviewedMetadata()) {
            mutableState.value = state.value.copy(message = "Gabarito pendente: registre uma nova versão com revisão documental antes de comparar. Ele está excluído dos denominadores.")
            return
        }
        performReview { actions.compare(it.runId, hash); it.runId }
    }
    fun selectPair(mode: ExtractionMode, id: String) {
        if (state.value.reviewBusy || state.value.relatedRuns.none { it.runId==id && it.mode==mode && it.outputBasis==OutputBasis.AUTOMATIC && it.state !in setOf(RunState.QUEUED,RunState.RUNNING) }) return
        mutableState.value = if (mode==ExtractionMode.BASELINE) state.value.copy(baselineId=id) else state.value.copy(assistedId=id)
    }
    fun showReport(hash: String, paired: Boolean = false) {
        val run=state.value.run ?: return
        if (state.value.reviewBusy || state.value.corpusCase==null || state.value.groundTruth.none { it.sha256==hash }) return
        val b=state.value.baselineId; val a=state.value.assistedId
        if (paired && (a==null || b==null)) return
        mutableState.value=state.value.copy(reviewBusy=true,message=null)
        val generation=++reportGeneration
        viewModelScope.launch {
            try {
                val pair=if(paired) actions.pair(requireNotNull(b),requireNotNull(a),hash) else null
                val report=pair?.report ?: actions.report(run.runId,hash)
                if (generation==reportGeneration && selectedRunId==run.runId && state.value.run?.runId==run.runId && repository.findRun(run.runId)!=null)
                    mutableState.value=state.value.copy(reviewBusy=false,report=report,pairedReport=pair)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if(generation==reportGeneration && selectedRunId==run.runId && state.value.run?.runId==run.runId) mutableState.value=state.value.copy(reviewBusy=false,
                message="Relatório indisponível. Confira o gabarito e selecione execuções automáticas finalizadas do mesmo documento e regra.") }
        }
    }
    fun closeReport() { reportGeneration++; mutableState.value=state.value.copy(report=null,pairedReport=null,reviewBusy=false) }
    private fun performReview(operation: suspend (ProcessingRun) -> String) {
        val run = state.value.run ?: return
        if (state.value.reviewBusy || run.state in setOf(RunState.QUEUED, RunState.RUNNING)) return
        mutableState.value = state.value.copy(reviewBusy = true, message = null)
        viewModelScope.launch {
            try {
                val next = operation(run)
                if (selectedRunId == run.runId) open(next)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (selectedRunId == run.runId) mutableState.value = state.value.copy(reviewBusy = false,
                    message = "Não foi possível salvar esta operação. Confira o rascunho e reabra a execução para verificar alterações concorrentes.")
            }
        }
    }

    fun showEvidence(observationId: String, field: String, page: Int) {
        val observation = state.value.rows.singleOrNull { it.observation.id == observationId }?.observation ?: return
        val evidence = observation.fieldEvidence.filter { it.field == field && it.region.page == page }
        if (evidence.isEmpty()) return
        val raw = when (field) {
            "analyteRaw" -> observation.analyteRaw; "resultRaw" -> observation.resultRaw
            "unitRaw" -> observation.unitRaw; "referenceRaw" -> observation.referenceRaw ?:
                state.value.rows.singleOrNull { it.observation.id == observationId }?.reference?.referenceRaw; else -> null
        }
        requestPreview(page, SelectedEvidence(EvidenceOrigin.EXTRACTED, extractedFieldLabel(field), raw, evidence))
    }
    fun showGroundTruthEvidence(caseId: String, version: String, observationId: String, field: String, page: Int) {
        val truth = state.value.groundTruth.singleOrNull { it.groundTruth.caseId == caseId && it.groundTruth.version == version }?.groundTruth ?: return
        val observation = truth.observations.singleOrNull { it.observationId == observationId } ?: return
        val evidence = observation.evidence.filter { it.field == field && it.region.page == page }
        if (evidence.isEmpty()) return
        val raw = when (field) {
            "analyteRaw" -> observation.analyteRaw; "resultRaw" -> observation.resultRaw
            "unitRaw" -> observation.unitRaw; "referenceRaw" -> observation.referenceRaw; else -> null
        }
        requestPreview(page, SelectedEvidence(EvidenceOrigin.GROUND_TRUTH, "${fieldLabel(field).replaceFirstChar { it.uppercase() }} do gabarito versão $version", raw, evidence))
    }
    private fun requestPreview(page: Int, selected: SelectedEvidence?) {
        val run = state.value.run ?: return
        if (page < 1 || state.value.pageCount?.let { page > it } == true) {
            closePreview()
            mutableState.value = state.value.copy(message = "A página desta evidência não está disponível no documento.")
            return
        }
        previewJob?.cancel()
        val generation = ++previewGeneration
        mutableState.value = state.value.copy(preview = null, previewPage = page, previewBusy = true, message = null, selectedEvidence = selected)
        previewJob = viewModelScope.launch {
            try {
                val bitmap = previewLoader.load(run.documentId, page)
                // Re-check eligibility after decode; deletion cannot republish a late private preview.
                if (generation != previewGeneration) return@launch
                if (repository.findRun(run.runId) == null) { unavailable(); return@launch }
                if (generation == previewGeneration && selectedRunId == run.runId && state.value.run?.runId == run.runId && state.value.previewPage == page)
                    mutableState.value = state.value.copy(preview = bitmap, previewBusy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == previewGeneration) mutableState.value = state.value.copy(preview = null, previewBusy = false,
                message = "Não foi possível abrir esta página da cópia privada.") }
        }
    }
    fun closePreview() {
        previewJob?.cancel()
        previewGeneration++
        mutableState.value = state.value.copy(preview = null, previewPage = null, previewBusy = false, selectedEvidence = null)
    }
    private fun unavailable(message: String = "Execução indisponível ou documento removido.") {
        reportGeneration++
        previewJob?.cancel()
        previewGeneration++
        mutableState.value = ResultsUiState(unavailable = true, message = message)
    }
}
