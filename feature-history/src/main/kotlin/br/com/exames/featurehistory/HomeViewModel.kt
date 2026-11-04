@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package br.com.exames.featurehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.exames.domain.summary.HomeState
import br.com.exames.domain.summary.ProjectHome
import br.com.exames.domain.validation.ProcessingRun
import br.com.exames.domain.validation.ValidationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** [home] is null only while the first read is in flight; failures become [HomeState.Unavailable], never Empty. */
data class HomeUiState(val loading: Boolean = true, val home: HomeState? = null)

@HiltViewModel
class HomeViewModel @Inject constructor(private val repository: ValidationRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState())
    val state = mutableState.asStateFlow()
    private var monitor: Job? = null
    init { reload() }

    fun reload() {
        monitor?.cancel()
        mutableState.value = HomeUiState()
        monitor = viewModelScope.launch {
            try {
                repository.observeDocuments().flatMapLatest { documents ->
                    if (documents.isEmpty()) flowOf(ProjectHome.project(documents, emptyMap()))
                    else combine(documents.map { d ->
                        // An unreadable run list is reported as null so it is never mistaken for "no runs".
                        repository.observeRuns(d.id).map<List<ProcessingRun>, List<ProcessingRun>?> { it }.catch { emit(null) }
                    }) { runs -> ProjectHome.project(documents, documents.indices.associate { documents[it].id to runs[it] }) }
                }.collect { mutableState.value = HomeUiState(loading = false, home = it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = HomeUiState(loading = false, home = HomeState.Unavailable) }
        }
    }
}
