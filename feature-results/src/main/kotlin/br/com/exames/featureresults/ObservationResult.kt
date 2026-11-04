package br.com.exames.featureresults

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*

@Composable
internal fun ObservationResult(row: ResultRow, ruleVersion: String, onPage: (Int) -> Unit,
    onEvidence: (String, String, Int) -> Unit, basis: OutputBasis = OutputBasis.AUTOMATIC,
    onCorrection: ((String, CorrectionField) -> Unit)? = null) {
    val observation = row.observation
    Card(Modifier.fillMaxWidth().testTag("observation-${observation.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(observation.analyteRaw, style = MaterialTheme.typography.titleMedium)
            Text(if (observation.confirmed || row.corrections.isNotEmpty()) "Revisão documental registrada" else "Sem revisão documental registrada")
            Text(if (basis == OutputBasis.AUTOMATIC) "Saída automática preservada. A revisão não elimina ambiguidades."
                else "Saída reavaliada. Os trechos destacados pertencem à fonte original; a revisão não elimina ambiguidades.")
            Text("Resultado: ${observation.resultRaw}")
            Text("Unidade: ${observation.unitRaw ?: "ausente"}")
        val referenceRaw = observation.referenceRaw ?: row.reference?.referenceRaw
        if (referenceRaw != null) Text("Referência: $referenceRaw")
        else if (observation.referenceCandidates.isEmpty()) Text("Referência: ausente")
        else {
            Text("Nenhuma referência selecionada de forma inequívoca.")
            Text("Referências candidatas registradas:")
            for (candidate in observation.referenceCandidates) Text(candidate.referenceRaw)
        }
            row.assessment?.let { assessment ->
                Text(when (assessment.status) {
                    AssessmentStatus.BELOW_RANGE -> "Abaixo da referência"
                    AssessmentStatus.WITHIN_RANGE -> "Dentro da referência"
                    AssessmentStatus.ABOVE_RANGE -> "Acima da referência"
                    AssessmentStatus.NEEDS_REVIEW -> "Precisa de revisão"
                    AssessmentStatus.NOT_CLASSIFIABLE -> "Não classificável"
                }, style = MaterialTheme.typography.titleSmall)
                Text(reason(assessment.reasonCode))
            } ?: Text("Avaliação indisponível")
            Text("Página ${observation.page} · Regra ${row.assessment?.ruleVersion ?: ruleVersion}")
            TextButton(onClick = { onPage(observation.page) }) { Text("Ver página ${observation.page} da fonte privada") }
            if (observation.fieldEvidence.isEmpty()) Text("Regiões dos campos indisponíveis nesta execução; abra a página inteira.")
            for ((field, page) in observation.fieldEvidence.map { it.field to it.region.page }.distinct()) {
                TextButton(modifier = Modifier.testTag("evidence-original-${observation.id}-$field-$page"),
                    onClick = { onEvidence(observation.id, field, page) }) {
                Text("Destacar ${extractedFieldLabel(field).lowercase()} — página $page")
                }
            }
            if (row.corrections.isNotEmpty()) {
                Text("Correções salvas — avaliação original preservada", style = MaterialTheme.typography.titleSmall)
                for (correction in row.corrections) {
                    Text(when (correction.field) {
                        CorrectionField.ANALYTE -> "Analito"; CorrectionField.RESULT -> "Resultado"
                        CorrectionField.UNIT -> "Unidade"; CorrectionField.REFERENCE -> "Referência"
                        CorrectionField.APPLICABILITY -> "Aplicabilidade"
                    })
                    Text("Antes: ${correction.oldValue ?: "ausente"}. Depois: ${correction.newValue ?: "ausente"}.")
                }
            }
            onCorrection?.let { edit ->
                for (field in CorrectionField.entries) TextButton(onClick = { edit(observation.id, field) },
                    modifier = Modifier.testTag("correct-${observation.id}-${field.name}")) {
                    Text("Corrigir ${correctionLabel(field).lowercase()}")
                }
            }
        }
    }
}
