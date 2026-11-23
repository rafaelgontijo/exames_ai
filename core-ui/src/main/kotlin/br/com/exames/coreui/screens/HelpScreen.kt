package br.com.exames.coreui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.*

/** Screen 4: Ajuda e revisão — Meu Exame (non-diagnostic documentary review guide). */
@Composable
fun HelpScreen(
    onBack: (() -> Unit)? = null,
    onOpenExams: () -> Unit,
    primaryActionLabel: String = "Abrir lista de exames",
    modifier: Modifier = Modifier,
) {
    val p = ExamesTheme.palette
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (onBack != null) {
            ExamesTopBar(title = "Ajuda e revisão", onBack = onBack)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onBack == null) {
                Text(
                    "Ajuda e revisão",
                    style = MaterialTheme.typography.headlineMedium,
                    color = p.title,
                    modifier = Modifier.semantics { heading() },
                )
            }

            // Primary Highlight Card
            ExamesHeroCard(
                title = "Confira sempre no laudo original",
                subtitle = "Cada valor extraído preserva o texto bruto e aponta a página exata do PDF. Leve o laudo original ao profissional que pediu o exame.",
                icon = ExamesIcons.Document,
                ctaText = primaryActionLabel,
                onCtaClick = onOpenExams,
            )

            // Status Legend Card
            ExamesCard {
                Text(
                    "O que significa cada marcação",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = p.title,
                    modifier = Modifier.semantics { heading() },
                )
                LegendRow(
                    label = "Na referência",
                    kind = ChipKind.INFO,
                    explanation = "Valor numérico dentro da faixa única informada no próprio laudo, com a mesma unidade.",
                )
                LegendRow(
                    label = "Revisar",
                    kind = ChipKind.REVIEW,
                    explanation = "Valor fora da faixa, referência ambígua ou com múltiplos critérios, unidade divergente ou vínculo incerto — confira no laudo.",
                )
                LegendRow(
                    label = "Sem faixa",
                    kind = ChipKind.INFO,
                    explanation = "Medição qualitativa ou sem faixa de referência comparável no documento.",
                )
            }

            // Privacy & Storage Card
            ExamesCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(ExamesIcons.ShieldLock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(
                        "Privacidade e armazenamento local",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = p.title,
                    )
                }
                Text(
                    "Os PDFs importados e os resultados extraídos ficam criptografados apenas neste aparelho com chaves protegidas pelo Android Keystore. O aplicativo funciona 100% offline, sem internet e sem backup automático.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.secondaryText,
                )
            }

            // Non-Diagnostic Boundary Notice
            ExamesAlertBox(
                title = "Este app não faz diagnóstico",
                body = "O Meu Exame apoia apenas a leitura e a conferência documental do laudo. Ele não emite diagnósticos, não recomenda condutas clínicas e não classifica urgência.",
            )

            Text(
                "Processamento 100% offline no aparelho. Esta leitura apoia a conferência do documento e não substitui a consulta com um profissional de saúde.",
                style = MaterialTheme.typography.bodySmall,
                color = p.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun LegendRow(label: String, kind: ChipKind, explanation: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusChip(label = label, kind = kind)
        Text(
            explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = ExamesTheme.palette.secondaryText,
        )
    }
}
