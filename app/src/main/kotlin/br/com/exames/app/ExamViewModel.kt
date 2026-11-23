package br.com.exames.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.exames.coreui.screens.*
import br.com.exames.datalocal.EncryptedExamHistoryStore
import br.com.exames.datalocal.ExamHistoryEntry
import br.com.exames.datalocal.ExamInputException
import br.com.exames.datalocal.ExamInputIssue
import br.com.exames.domain.extraction.AssessedExam
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExamSummaryProjection
import br.com.exames.inferencelocal.LocalTextInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface AppNavEvent {
    data class OpenProcessing(val examId: String) : AppNavEvent
    data class OpenResults(val examId: String) : AppNavEvent
    data object BackToHome : AppNavEvent
}

internal data class OpenedExamState(
    val entry: ExamHistoryEntry,
    val extraction: ExamExtraction,
    val assessed: AssessedExam,
    val filter: ResultFilterOption = ResultFilterOption.ALL,
)

internal data class ExamAppState(
    val loading: Boolean = true,
    val history: List<ExamHistoryEntry> = emptyList(),
    val activeExamId: String? = null,
    val processing: ProcessingUiState? = null,
    val openedExam: OpenedExamState? = null,
    val selectedEvidenceId: String? = null,
    val notice: String? = null,
    val navEvent: AppNavEvent? = null,
) {
    val historyCards: List<ExamCardItemUi> get() = history.map(ExamUiMapper::toHistoryCard)

    fun resultsUi(): ResultsUiState? {
        val current = openedExam ?: return null
        return ExamUiMapper.toResultsUi(
            entry = current.entry,
            assessed = current.assessed,
            activeFilter = current.filter,
            notice = notice,
        )
    }

    fun evidenceUi(): EvidenceUiState? {
        val current = openedExam ?: return null
        val targetId = selectedEvidenceId ?: current.assessed.firstReviewObservation()?.observation?.sourceResult?.id ?: return null
        val match = current.assessed.allObservations().firstOrNull { it.observation.sourceResult.id == targetId } ?: return null
        return ExamUiMapper.toEvidenceUi(current.entry.id, current.assessed.pages.size, match)
    }
}

internal class ExamViewModel(application: Application) : AndroidViewModel(application) {
    private val store = EncryptedExamHistoryStore(application)
    private val runner = ExamRunner(store, LocalTextInference(application))
    private val mutableState = MutableStateFlow(ExamAppState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null

    init {
        viewModelScope.launch {
            refreshHistory(initialLoad = true)
        }
    }

    fun consumeNavEvent() {
        mutableState.update { it.copy(navEvent = null) }
    }

    fun clearNotice() {
        mutableState.update { it.copy(notice = null) }
    }

    fun selectPdf(uri: Uri?) {
        if (uri == null || work?.isActive == true) return
        mutableState.update {
            it.copy(
                notice = null,
                processing = ProcessingUiState(message = "Abrindo o PDF", cancellable = true),
            )
        }
        work = viewModelScope.launch {
            var createdId: String? = null
            try {
                val id = store.accept(uri)
                createdId = id
                mutableState.update {
                    it.copy(
                        activeExamId = id,
                        processing = ProcessingUiState(message = "Analisando laudo em PDF", cancellable = true),
                        navEvent = AppNavEvent.OpenProcessing(id),
                    )
                }
                val finishedPages = linkedMapOf<Int, PageProgressUi>()
                val extraction = runner.extract(
                    id = id,
                    onProgress = { page, totalPages, block, blocks ->
                        mutableState.update { current ->
                            if (current.processing?.cancellable != true) return@update current
                            val pageList = (1..totalPages).map { p ->
                                finishedPages[p] ?: when {
                                    p == page -> PageProgressUi(p, PageProgressStatus.IN_PROGRESS)
                                    else -> PageProgressUi(p, PageProgressStatus.PENDING)
                                }
                            }
                            current.copy(
                                processing = ProcessingUiState(
                                    message = "Extraindo com IA",
                                    page = page,
                                    totalPages = totalPages,
                                    block = block,
                                    totalBlocks = blocks,
                                    pages = pageList,
                                    cancellable = true,
                                ),
                            )
                        }
                    },
                    onPageFinished = { outcome ->
                        val status = if (outcome.issues.isNotEmpty()) PageProgressStatus.ISSUE else PageProgressStatus.COMPLETED
                        finishedPages[outcome.page] = PageProgressUi(outcome.page, status, outcome.observations.size)
                    },
                )
                val savedEntry = store.save(id, extraction)
                val history = store.list()
                ensureActive()
                val assessed = ExamSummaryProjection.project(extraction)
                mutableState.update {
                    it.copy(
                        history = history,
                        activeExamId = null,
                        processing = null,
                        openedExam = OpenedExamState(savedEntry, extraction, assessed),
                        selectedEvidenceId = null,
                        notice = null,
                        navEvent = AppNavEvent.OpenResults(id),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ExamInputException) {
                val notice = when (failure.issue) {
                    ExamInputIssue.NOT_PDF -> "Selecione um arquivo PDF digital."
                    ExamInputIssue.EMPTY -> "O arquivo selecionado está vazio."
                    ExamInputIssue.TOO_LARGE -> "O arquivo excede o limite de tamanho."
                    ExamInputIssue.UNREADABLE -> "Não foi possível ler o arquivo selecionado."
                }
                mutableState.update {
                    it.copy(activeExamId = null, processing = null, notice = notice)
                }
            } catch (failure: Exception) {
                val notice = when (failure.message) {
                    "PDF_PAGE_LIMIT" -> "O PDF excede o limite de páginas."
                    "PDF_OPEN_FAILED" -> "Não foi possível abrir o PDF. Verifique se ele exige senha ou está danificado."
                    else -> "A extração foi interrompida por uma falha no arquivo ou no armazenamento."
                }
                val history = runCatching { store.list() }.getOrElse { mutableState.value.history }
                mutableState.update {
                    it.copy(
                        history = history,
                        activeExamId = null,
                        processing = null,
                        notice = notice,
                        navEvent = if (createdId != null) AppNavEvent.BackToHome else null,
                    )
                }
            }
        }
    }

    fun cancelProcessing() {
        val current = mutableState.value
        if (current.processing?.cancellable != true) return
        val activeJob = work
        val activeId = current.activeExamId
        mutableState.update {
            it.copy(processing = ProcessingUiState(message = "Cancelando a extração", cancellable = false))
        }
        work = viewModelScope.launch {
            activeJob?.cancelAndJoin()
            if (activeId != null) {
                runCatching { store.cancelActive(activeId) }
            }
            val history = runCatching { store.list() }.getOrElse { emptyList() }
            mutableState.update {
                it.copy(
                    history = history,
                    activeExamId = null,
                    processing = null,
                    notice = "Extração cancelada.",
                    navEvent = AppNavEvent.BackToHome,
                )
            }
        }
    }

    fun openExam(examId: String) {
        if (work?.isActive == true) return
        viewModelScope.launch {
            val record = runCatching { store.load(examId) }.getOrNull()
            val extraction = record?.extraction
            when {
                record == null -> {
                    refreshHistory(notice = "Não foi possível reabrir o exame selecionado.")
                }
                extraction == null -> {
                    mutableState.update {
                        it.copy(notice = "A extração deste exame foi interrompida. Você pode excluí-lo ou importar novamente.")
                    }
                }
                else -> {
                    val assessed = ExamSummaryProjection.project(extraction)
                    mutableState.update {
                        it.copy(
                            openedExam = OpenedExamState(record.entry, extraction, assessed),
                            selectedEvidenceId = null,
                            notice = null,
                            navEvent = AppNavEvent.OpenResults(examId),
                        )
                    }
                }
            }
        }
    }

    fun selectFilter(option: ResultFilterOption) {
        mutableState.update { current ->
            val opened = current.openedExam ?: return@update current
            current.copy(openedExam = opened.copy(filter = option))
        }
    }

    fun selectEvidence(observationId: String) {
        mutableState.update { it.copy(selectedEvidenceId = observationId) }
    }

    fun deleteExam(examId: String) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            val removed = runCatching { store.delete(examId) }.isSuccess
            val history = runCatching { store.list() }.getOrElse { emptyList() }
            mutableState.update { current ->
                val wasOpened = current.openedExam?.entry?.id == examId
                current.copy(
                    history = history,
                    openedExam = if (wasOpened) null else current.openedExam,
                    selectedEvidenceId = if (wasOpened) null else current.selectedEvidenceId,
                    notice = if (removed) "Extração descartada." else "Não foi possível descartar. Tente novamente.",
                    navEvent = if (wasOpened) AppNavEvent.BackToHome else null,
                )
            }
        }
    }

    fun deleteAllExams() {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            val removed = runCatching { store.deleteAll() }.isSuccess
            val history = runCatching { store.list() }.getOrElse { emptyList() }
            mutableState.update {
                it.copy(
                    history = history,
                    openedExam = null,
                    selectedEvidenceId = null,
                    notice = if (removed) "Todos os exames foram apagados." else "Não foi possível apagar os exames.",
                    navEvent = AppNavEvent.BackToHome,
                )
            }
        }
    }

    suspend fun preview(examId: String, page: Int): Bitmap {
        var allocated: Bitmap? = null
        try {
            val pdf = store.pdf.open(examId)
            try {
                return withContext(Dispatchers.IO) {
                    pdf.readPage(page).use { snapshot ->
                        checkNotNull(snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, false)).also { allocated = it }
                    }
                }
            } finally {
                pdf.closeAsync()
            }
        } catch (failure: Throwable) {
            allocated?.recycle()
            throw failure
        }
    }

    private suspend fun refreshHistory(initialLoad: Boolean = false, notice: String? = null) {
        val loaded = runCatching { store.list() }
        mutableState.update { current ->
            if (loaded.isSuccess) {
                current.copy(
                    loading = false,
                    history = loaded.getOrThrow(),
                    notice = notice ?: current.notice,
                )
            } else {
                current.copy(
                    loading = false,
                    notice = notice ?: if (initialLoad) "Não foi possível abrir o histórico local." else current.notice,
                )
            }
        }
    }
}
