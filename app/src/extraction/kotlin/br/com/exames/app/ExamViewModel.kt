package br.com.exames.app

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.exames.datalocal.CurrentExamStore
import br.com.exames.datalocal.ExamInputException
import br.com.exames.datalocal.ExamInputIssue
import br.com.exames.domain.extraction.ExamExtraction
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

internal sealed interface ExamScreenState {
    data class Entry(val notice: String? = null, val canDiscard: Boolean = false) : ExamScreenState
    data class Working(val message: String, val page: Int = 0, val pages: Int = 0, val block: Int = 0, val blocks: Int = 0,
        val cancellable: Boolean = true) : ExamScreenState
    data class Finished(val id: String, val extraction: ExamExtraction, val notice: String? = null) : ExamScreenState
}

internal class ExamViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CurrentExamStore(application)
    private val run = ExamRunner(store, LocalTextInference(application))
    private val mutableState = MutableStateFlow<ExamScreenState>(ExamScreenState.Working("Abrindo o aplicativo", cancellable = false))
    val state = mutableState.asStateFlow()
    private var work: Job? = null

    init {
        work = viewModelScope.launch {
            mutableState.value = try {
                val current = store.current()
                when {
                    current == null -> ExamScreenState.Entry()
                    current.extraction == null -> ExamScreenState.Entry("A extração foi interrompida.", canDiscard = true)
                    else -> ExamScreenState.Finished(current.id, current.extraction!!)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ExamScreenState.Entry("Não foi possível reabrir a extração.", canDiscard = true)
            }
        }
    }

    fun select(uri: Uri?) {
        if (uri == null || work?.isActive == true) return
        val prior = mutableState.value
        mutableState.value = ExamScreenState.Working("Abrindo o PDF")
        work = viewModelScope.launch {
            try {
                val id = store.accept(uri)
                val result = run.extract(id) { page, pages, block, blocks ->
                    mutableState.update { current ->
                        if ((current as? ExamScreenState.Working)?.cancellable == true)
                            ExamScreenState.Working("Extraindo com IA", page, pages, block, blocks)
                        else current
                    }
                }
                store.save(id, result)
                ensureActive()
                mutableState.value = ExamScreenState.Finished(id, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ExamInputException) {
                val notice = when (failure.issue) {
                    ExamInputIssue.NOT_PDF -> "Selecione um arquivo PDF digital."
                    ExamInputIssue.EMPTY -> "O arquivo selecionado está vazio."
                    ExamInputIssue.TOO_LARGE -> "O arquivo excede o limite de tamanho."
                    ExamInputIssue.UNREADABLE -> "Não foi possível ler o arquivo selecionado."
                }
                mutableState.value = when (prior) {
                    is ExamScreenState.Finished -> prior.copy(notice = notice)
                    is ExamScreenState.Entry -> prior.copy(notice = notice)
                    is ExamScreenState.Working -> ExamScreenState.Entry(notice)
                }
            } catch (failure: Exception) {
                val notice = when (failure.message) {
                    "PDF_PAGE_LIMIT" -> "O PDF excede o limite de páginas."
                    "PDF_OPEN_FAILED" -> "Não foi possível abrir o PDF. Verifique se ele exige senha ou está danificado."
                    else -> "A extração foi interrompida por uma falha no arquivo ou no armazenamento."
                }
                mutableState.value = ExamScreenState.Entry(notice, canDiscard = true)
            }
        }
    }

    fun cancel() {
        if ((mutableState.value as? ExamScreenState.Working)?.cancellable != true) return
        val active = work
        mutableState.value = ExamScreenState.Working("Cancelando a extração", cancellable = false)
        work = viewModelScope.launch {
            active?.cancelAndJoin()
            remove("Extração cancelada.")
        }
    }

    fun discard() {
        if (work?.isActive == true) return
        mutableState.value = ExamScreenState.Working("Descartando o PDF e os resultados", cancellable = false)
        work = viewModelScope.launch { remove("Extração descartada.") }
    }

    suspend fun preview(id: String, page: Int): Bitmap {
        var allocated: Bitmap? = null
        try {
            val pdf = store.pdf.open(id)
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

    private suspend fun remove(notice: String) {
        mutableState.value = try {
            store.discard()
            ExamScreenState.Entry(notice)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExamScreenState.Entry("Não foi possível descartar. Tente novamente.", canDiscard = true)
        }
    }
}
