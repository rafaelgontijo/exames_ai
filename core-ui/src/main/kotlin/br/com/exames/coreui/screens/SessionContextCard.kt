package br.com.exames.coreui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.*

/**
 * Reusable ephemeral clinical context card (sex, age, fasting, pregnancy).
 * State lives strictly in memory during the current reading flow and is never persisted.
 */
@Composable
fun SessionContextCard(
    context: ClinicalContextUi,
    onUpdate: (ClinicalContextUi) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    titleWhenEmpty: String = "Dados básicos desta leitura (opcional)",
    subtitleWhenEmpty: String = "Informe antes de ler o PDF para aplicar faixas por sexo, idade ou jejum. Não fica salvo.",
) {
    val p = ExamesTheme.palette
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val summary = context.summaryLabel()

    ExamesCard(
        modifier = modifier.testTag("session-context-card"),
        borderedControl = !context.isEmpty || expanded,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (context.isEmpty) titleWhenEmpty else "Contexto desta leitura ativo",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = p.title,
                )
                Text(
                    if (context.isEmpty) subtitleWhenEmpty else summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = p.secondaryText,
                )
            }
            ExamesTextButton(onClick = { expanded = !expanded }) {
                Text(
                    when {
                        expanded -> "Recolher"
                        context.isEmpty -> "Informar"
                        else -> "Editar"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (expanded) {
            HorizontalDivider(color = p.decorativeBorder)

            // Biological sex (toggleable chips: tap again to clear)
            Text(
                "Sexo biológico",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExamesFilterChip(
                    label = "Feminino",
                    isSelected = context.sex == ContextSexOption.FEMALE,
                    onClick = {
                        val next = if (context.sex == ContextSexOption.FEMALE) ContextSexOption.UNSPECIFIED else ContextSexOption.FEMALE
                        onUpdate(context.copy(sex = next))
                    },
                )
                ExamesFilterChip(
                    label = "Masculino",
                    isSelected = context.sex == ContextSexOption.MALE,
                    onClick = {
                        val next = if (context.sex == ContextSexOption.MALE) ContextSexOption.UNSPECIFIED else ContextSexOption.MALE
                        onUpdate(
                            context.copy(
                                sex = next,
                                pregnancy = if (next == ContextSexOption.MALE) ContextPregnancyOption.UNSPECIFIED else context.pregnancy,
                            ),
                        )
                    },
                )
            }

            // Age in years
            OutlinedTextField(
                value = context.ageInput,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(3)
                    val parsed = digits.toIntOrNull()
                    if (digits.isEmpty() || (parsed != null && parsed in 0..120)) {
                        onUpdate(context.copy(ageInput = digits))
                    }
                },
                label = { Text("Idade em anos (opcional)") },
                placeholder = { Text("Ex.: 35") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .testTag("context-age-input"),
            )

            // Fasting state (toggleable chips)
            Text(
                "Jejum na coleta",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExamesFilterChip(
                    label = "Em jejum",
                    isSelected = context.fasting == ContextFastingOption.FASTING,
                    onClick = {
                        val next = if (context.fasting == ContextFastingOption.FASTING) ContextFastingOption.UNSPECIFIED else ContextFastingOption.FASTING
                        onUpdate(context.copy(fasting = next))
                    },
                )
                ExamesFilterChip(
                    label = "Sem jejum",
                    isSelected = context.fasting == ContextFastingOption.NON_FASTING,
                    onClick = {
                        val next = if (context.fasting == ContextFastingOption.NON_FASTING) ContextFastingOption.UNSPECIFIED else ContextFastingOption.NON_FASTING
                        onUpdate(context.copy(fasting = next))
                    },
                )
            }

            // Pregnancy (shown only when biological sex is FEMALE)
            if (context.sex == ContextSexOption.FEMALE) {
                Text(
                    "Gestação",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExamesFilterChip(
                        label = "Não gestante",
                        isSelected = context.pregnancy == ContextPregnancyOption.NOT_PREGNANT,
                        onClick = {
                            val next = if (context.pregnancy == ContextPregnancyOption.NOT_PREGNANT) {
                                ContextPregnancyOption.UNSPECIFIED
                            } else {
                                ContextPregnancyOption.NOT_PREGNANT
                            }
                            onUpdate(context.copy(pregnancy = next))
                        },
                    )
                    ExamesFilterChip(
                        label = "Gestante",
                        isSelected = context.pregnancy == ContextPregnancyOption.PREGNANT,
                        onClick = {
                            val next = if (context.pregnancy == ContextPregnancyOption.PREGNANT) {
                                ContextPregnancyOption.UNSPECIFIED
                            } else {
                                ContextPregnancyOption.PREGNANT
                            }
                            onUpdate(context.copy(pregnancy = next))
                        },
                    )
                }
            }

            Text(
                "Usado apenas nesta leitura para aplicar faixas do próprio laudo. Nunca é salvo no aparelho.",
                style = MaterialTheme.typography.bodySmall,
                color = p.secondaryText,
            )

            if (!context.isEmpty) {
                ExamesTextButton(onClick = onClear) {
                    Text("Limpar contexto", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else if (!context.isEmpty) {
            ExamesTextButton(onClick = onClear) {
                Text("Limpar contexto", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
