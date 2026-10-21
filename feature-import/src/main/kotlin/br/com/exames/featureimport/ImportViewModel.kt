package br.com.exames.featureimport

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.exames.datadocuments.SafDocumentReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ImportUiState(val busy: Boolean = false, val selected: Boolean = false, val message: String? = null)
class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val reader = SafDocumentReader(application.contentResolver)
    private val mutableState = MutableStateFlow(ImportUiState())
    val state = mutableState.asStateFlow()
    fun select(uri: Uri) {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.value = ImportUiState(busy = true)
            try {
                reader.inspect(uri)
                mutableState.value = ImportUiState(selected = true,
                    message = "Formato aceito. A cópia privada, a prévia e o parse serão integrados na próxima etapa.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutableState.value = ImportUiState(message = "Não foi possível abrir o documento. Use PDF, JPEG ou PNG de até 20 MB.")
            }
        }
    }
}
