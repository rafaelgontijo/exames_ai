package br.com.exames.featureresults

import br.com.exames.coreui.ExamesTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*

@Composable
internal fun GroundTruthResult(snapshot: GroundTruthSnapshot, onEvidence: (String, String, Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val truth = snapshot.groundTruth
        Text("Gabarito versão ${truth.version}")
        Text(if (truth.reviewState == ReviewState.REVIEWED) "Revisão documental registrada" else "Revisão pendente — fora das métricas de acerto")
        for (expected in truth.observations) {
            Text("${expected.analyteRaw}: ${expected.resultRaw} ${expected.unitRaw.orEmpty()}")
            Text("Referência esperada: ${expected.referenceRaw ?: "ausente"}")
            Text("Estado esperado: " + when (expected.expectedStatus) {
                ExpectedStatus.BELOW_RANGE -> "Abaixo da referência"; ExpectedStatus.WITHIN_RANGE -> "Dentro da referência"
                ExpectedStatus.ABOVE_RANGE -> "Acima da referência"; ExpectedStatus.NEEDS_REVIEW -> "Precisa de revisão"
                ExpectedStatus.NOT_CLASSIFIABLE -> "Não classificável"
            })
            Text(ReasonCode.entries.firstOrNull { it.name == expected.expectedReason }?.let(::reason)
                ?: "Motivo do gabarito indisponível nesta versão.")
            for ((field, page) in expected.evidence.map { it.field to it.region.page }.distinct()) {
                ExamesTextButton(modifier = Modifier.testTag("evidence-truth-${truth.version}-${expected.observationId}-$field-$page"),
                    onClick = { onEvidence(expected.observationId, field, page) }) {
                    Text("Destacar ${fieldLabel(field)} do gabarito — página $page")
                }
            }
        }
    } }
}
