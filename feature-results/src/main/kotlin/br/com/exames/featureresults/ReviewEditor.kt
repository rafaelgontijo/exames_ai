package br.com.exames.featureresults

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import br.com.exames.domain.model.CorrectionField

internal fun correctionLabel(field: CorrectionField) = when (field) {
    CorrectionField.ANALYTE -> "Analito"; CorrectionField.RESULT -> "Resultado"; CorrectionField.UNIT -> "Unidade"
    CorrectionField.REFERENCE -> "Referência"; CorrectionField.APPLICABILITY -> "Aplicabilidade"
}

@Composable
internal fun ReviewEditor(draft: ReviewDraft, busy: Boolean, message: String?, onChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = {
        Text(if (draft is ReviewDraft.Correction) "Corrigir extração" else "Revisar gabarito em nova versão")
    }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (draft) {
                is ReviewDraft.Correction -> {
                    Text("${correctionLabel(draft.field)} — antes: ${draft.old ?: "ausente"}")
                    Text("O registro original e sua avaliação serão preservados. Reavalie em uma nova saída para aplicar a correção. Ambiguidades permanecem em revisão.")
                    if (draft.field == CorrectionField.APPLICABILITY) Text("Valores: APPLICABLE, UNKNOWN ou NOT_APPLICABLE. Uma escolha manual não resolve contexto clínico desconhecido.")
                }
                is ReviewDraft.Truth -> {
                    Text("Edite o contrato JSON independente: campos, evidências, entradas canônicas e respostas esperadas. A nova versão começa pendente; para marcar REVIEWED, informe reviewerId e reviewedAt em ISO-8601.")
                    Text("Nenhuma resposta é calculada a partir da extração. O gabarito anterior e suas comparações serão preservados.")
                }
            }
            // TextFieldValue overload: clinical drafts are owned by the ViewModel, never rememberSaveable.
            OutlinedTextField(value = draft.text, onValueChange = onChange, enabled = !busy,
                label = { Text(if (draft is ReviewDraft.Correction) "Depois" else "Novo gabarito JSON") },
                modifier = Modifier.fillMaxWidth().testTag("review-draft"))
            message?.let { Text(it) }
        }
    }, confirmButton = { TextButton(onClick = onSave, enabled = !busy, modifier = Modifier.testTag("review-save")) {
        Text(if (busy) "Salvando" else "Salvar novo registro")
    } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Descartar rascunho") } })
}
