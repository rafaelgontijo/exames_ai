package br.com.exames.coreui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.*
import kotlin.math.roundToInt

/** Screen 5: Processando laudo — Meu Exame. */
@Composable
fun ProcessingScreen(
    state: ProcessingUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = ExamesTheme.palette
    val fraction = state.progressFraction

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val isCloud = state.executionMode == ExecutionModeUi.CLOUD_PRIVATE_FALLBACK
        ExamesTopBar(
            title = "Lendo seu exame",
            trailing = {
                StatusChip(
                    label = if (isCloud) "Nuvem privada" else "Offline no aparelho",
                    kind = ChipKind.INFO,
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Primary Progress Hero Card
            val progressSubtitle = buildString {
                if (state.totalPages > 0) append("Página ${state.page} de ${state.totalPages}")
                if (state.totalBlocks > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("Bloco ${state.block} de ${state.totalBlocks}")
                }
                if (isEmpty()) append("Preparando leitura local")
            }

            ExamesHeroCard(
                title = state.message,
                subtitle = progressSubtitle,
                icon = ExamesIcons.Document,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                extraContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (fraction != null) {
                            val percent = (fraction * 100).roundToInt()
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .semantics { contentDescription = "Progresso da leitura: $percent por cento" },
                                color = p.heroActionContainer,
                                trackColor = p.onHeroSubtext.copy(alpha = 0.28f),
                            )
                            Text(
                                "$percent% concluído",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = p.onHeroContainer,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .semantics { contentDescription = "Leitura local em andamento" },
                                color = p.heroActionContainer,
                                trackColor = p.onHeroSubtext.copy(alpha = 0.28f),
                            )
                        }
                        Text(
                            if (isCloud) {
                                "Fallback privado na nuvem (sem retenção) lê cada faixa em ordem documental com tarja local de PII em PDFs digitais."
                            } else {
                                "A IA local lê cada tabela em ordem documental e vincula os trechos ao PDF original."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = p.onHeroSubtext,
                        )
                    }
                },
            )

            // Page-by-page status card
            if (state.pages.isNotEmpty()) {
                ExamesCard {
                    Text(
                        "Andamento por página",
                        style = MaterialTheme.typography.titleMedium,
                        color = p.title,
                        modifier = Modifier.semantics { heading() },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val pendingPages = state.pages.filter { it.status == PageProgressStatus.PENDING }
                        val activeAndDone = state.pages.filter { it.status != PageProgressStatus.PENDING }
                        activeAndDone.forEach { item ->
                            PageProgressRow(item)
                        }
                        if (pendingPages.isNotEmpty()) {
                            val label = if (pendingPages.size == 1) {
                                "Página ${pendingPages.first().page}"
                            } else {
                                "Páginas ${pendingPages.first().page} a ${pendingPages.last().page}"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(ExamesIcons.Clock, contentDescription = null, tint = p.secondaryText, modifier = Modifier.size(18.dp))
                                    Text(label, style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
                                }
                                Text("Aguardando", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                            }
                        }
                    }
                }
            }

            // Privacy Box
            ExamesInfoBox {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(ExamesIcons.ShieldLock, contentDescription = null, tint = p.onInfoContainer, modifier = Modifier.size(20.dp))
                    Text(
                        if (isCloud) {
                            "Envio criptografado (TLS) apenas das faixas do laudo, sem armazenamento nem treino na nuvem. Você pode sair do app e acompanhar pela barra de notificações; avisaremos ao terminar."
                        } else {
                            "Seus dados permanecem privados e cifrados apenas neste aparelho. Você pode sair do app e acompanhar pela barra de notificações; avisaremos ao terminar."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.onInfoContainer,
                    )
                }
            }

            if (state.cancellable) {
                SecondaryAction(
                    text = "Cancelar leitura",
                    onClick = onCancel,
                )
            }
        }
    }
}

@Composable
private fun PageProgressRow(item: PageProgressUi) {
    val p = ExamesTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            val icon = when (item.status) {
                PageProgressStatus.COMPLETED -> ExamesIcons.Check
                PageProgressStatus.ISSUE -> ExamesIcons.AlertCircle
                PageProgressStatus.IN_PROGRESS, PageProgressStatus.PENDING -> ExamesIcons.Clock
            }
            val tint = when (item.status) {
                PageProgressStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                PageProgressStatus.ISSUE -> p.onReviewContainer
                PageProgressStatus.IN_PROGRESS -> p.title
                PageProgressStatus.PENDING -> p.secondaryText
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(
                "Página ${item.page}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.status == PageProgressStatus.IN_PROGRESS) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        when (item.status) {
            PageProgressStatus.COMPLETED -> StatusChip(
                label = if (item.verifiedCount == 1) "1 item verificado" else "${item.verifiedCount} itens verificados",
                kind = ChipKind.INFO,
            )
            PageProgressStatus.ISSUE -> StatusChip(
                label = if (item.verifiedCount > 0) "${item.verifiedCount} itens · aviso" else "Conferir página",
                kind = ChipKind.REVIEW,
            )
            PageProgressStatus.IN_PROGRESS -> StatusChip(
                label = "Lendo agora...",
                kind = ChipKind.INFO,
            )
            PageProgressStatus.PENDING -> Text("Aguardando", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
        }
    }
}
