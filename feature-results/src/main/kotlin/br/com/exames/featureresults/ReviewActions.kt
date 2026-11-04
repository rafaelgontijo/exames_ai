package br.com.exames.featureresults

import androidx.compose.ui.text.input.TextFieldValue
import br.com.exames.domain.model.CorrectionField
import br.com.exames.domain.validation.*

interface ReviewActions {
    suspend fun correct(runId: String, observationId: String, field: CorrectionField, old: String?, value: String?, id: String)
    suspend fun reevaluate(parentId: String, id: String): ProcessingRun
    suspend fun revise(documentId: String, parentHash: String, draft: String)
    suspend fun reextract(documentId: String, parentId: String, id: String): ProcessingRun
    suspend fun compare(runId: String, truthHash: String)
    suspend fun report(runId: String, truthHash: String): LocalValidationReport
    suspend fun pair(baselineId: String, assistedId: String, truthHash: String): PairedValidationReport
}

/** Memory only: never a saveable Compose value or navigation argument. */
sealed interface ReviewDraft {
    val text: TextFieldValue
    data class Correction(val observationId: String, val field: CorrectionField, val old: String?,
        val id: String, override val text: TextFieldValue) : ReviewDraft
    data class Truth(val parentHash: String, override val text: TextFieldValue) : ReviewDraft
}
