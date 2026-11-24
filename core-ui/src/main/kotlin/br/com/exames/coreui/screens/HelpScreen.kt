package br.com.exames.coreui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
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
    hybridSettings: HybridSettingsUi? = null,
    onToggleCloudFallback: (Boolean) -> Unit = {},
    onToggleWifiOnly: (Boolean) -> Unit = {},
    onStartDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onDeleteLocalModel: () -> Unit = {},
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

            // Hybrid Local-First & On-Demand Weights Management Card
            if (hybridSettings != null) {
                HybridAiSettingsCard(
                    settings = hybridSettings,
                    onToggleCloudFallback = onToggleCloudFallback,
                    onToggleWifiOnly = onToggleWifiOnly,
                    onStartDownload = onStartDownload,
                    onCancelDownload = onCancelDownload,
                    onDeleteLocalModel = onDeleteLocalModel,
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
                        "Privacidade e arquitetura Local-First",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = p.title,
                    )
                }
                Text(
                    "O Meu Exame prioriza o processamento local no aparelho com Gemma 4 E2B sempre que o hardware (≥ 6 GB de RAM e GPU 64-bit) e os pesos verificados por SHA-256 estiverem disponíveis. Os PDFs e resultados ficam criptografados apenas no aparelho via Android Keystore, sem backup automático.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.secondaryText,
                )
                Text(
                    "Quando o modelo local não estiver disponível, você pode optar explicitamente pelo fallback privado na nuvem: PDFs digitais recebem tarja local de PII em memória antes do envio criptografado (TLS) e o backend não grava dados nem usa exames para treinamento.",
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
                "Processamento Local-First no aparelho com fallback privado opcional (sem retenção). Esta leitura apoia a conferência do documento e não substitui a consulta com um profissional de saúde.",
                style = MaterialTheme.typography.bodySmall,
                color = p.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun HybridAiSettingsCard(
    settings: HybridSettingsUi,
    onToggleCloudFallback: (Boolean) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteLocalModel: () -> Unit,
) {
    val p = ExamesTheme.palette
    ExamesCard {
        Text(
            "Modo de IA e pesos locais",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = p.title,
            modifier = Modifier.semantics { heading() },
        )
        StatusChip(
            label = settings.activeModeLabel,
            kind = if (settings.isCloudActive) ChipKind.REVIEW else ChipKind.INFO,
        )
        settings.downloadStatusMessage?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
        }
        if (settings.localModelStatus == LocalModelStatusUi.DOWNLOADING) {
            val fraction = settings.downloadFraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
            }
            SecondaryAction(text = "Cancelar download dos pesos", onClick = onCancelDownload)
        } else if (settings.canDownloadLocal && settings.localModelStatus != LocalModelStatusUi.READY) {
            SecondaryAction(text = "Baixar modelo local (2,58 GB)", onClick = onStartDownload)
        }
        if (settings.canRemoveLocal) {
            ExamesTextButton(onClick = onDeleteLocalModel) {
                Text("Remover pesos locais para liberar espaço")
            }
        }

        HorizontalDivider(color = p.decorativeBorder)

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Baixar pesos somente no Wi-Fi",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Evita consumo de dados móveis ao baixar o modelo local.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.secondaryText,
                )
            }
            Switch(
                checked = settings.downloadOnWifiOnly,
                onCheckedChange = onToggleWifiOnly,
                modifier = Modifier
                    .widthIn(min = MinTouchTarget)
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = "Baixar pesos somente no Wi-Fi" },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Permitir fallback privado na nuvem",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (settings.cloudFallbackOptIn) {
                        "Ativo por consentimento. Desative para manter o app estritamente offline."
                    } else {
                        "Desativado. O app solicitará consentimento antes de qualquer envio."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = p.secondaryText,
                )
            }
            Switch(
                checked = settings.cloudFallbackOptIn,
                onCheckedChange = onToggleCloudFallback,
                modifier = Modifier
                    .widthIn(min = MinTouchTarget)
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = "Permitir fallback privado na nuvem" },
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
