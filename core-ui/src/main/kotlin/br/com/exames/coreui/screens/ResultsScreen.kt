package br.com.exames.coreui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.*

/** Screen 3: Resultado — Meu Exame (with status filters and evidence links). */
@Composable
fun ResultsScreen(
    state: ResultsUiState,
    onBack: () -> Unit,
    onSelectFilter: (ResultFilterOption) -> Unit,
    onOpenEvidence: (String) -> Unit,
    onOpenHelp: () -> Unit,
    onSelectAnotherPdf: () -> Unit,
    onDeleteExam: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = ExamesTheme.palette
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ExamesTopBar(
            title = "Laudo laboratorial · ${state.dateLabel}",
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = ScreenMargin, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Top Summary Banner
            item(key = "summary") {
                Column(
                    modifier = Modifier.testTag("results-summary"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        state.totalCount == 0 -> {
                            ExamesReviewBanner(
                                title = "Nenhum resultado confirmado pela IA.",
                                subtitle = "Confira as páginas sinalizadas ou tente outro PDF digital.",
                            )
                        }
                        state.reviewCount > 0 -> {
                            val title = if (state.reviewCount == 1) {
                                "1 item pede conferência"
                            } else {
                                "${state.reviewCount} itens pedem conferência"
                            }
                            ExamesReviewBanner(
                                title = title,
                                subtitle = "Confira os trechos originais no laudo antes de tirar conclusões",
                                ctaText = if (state.primaryEvidenceItemId != null) "Ver no laudo" else null,
                                onCtaClick = state.primaryEvidenceItemId?.let { id -> { onOpenEvidence(id) } },
                            )
                        }
                        else -> {
                            ExamesInfoBox(title = "${state.totalCount} resultados dentro da referência informada") {
                                Text(
                                    "Todos os itens comparáveis ficaram dentro da faixa informada no documento. Confira sempre o laudo original.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = p.onInfoContainer,
                                )
                                state.primaryEvidenceItemId?.let { id ->
                                    SecondaryAction(text = "Ver no laudo", onClick = { onOpenEvidence(id) })
                                }
                            }
                        }
                    }
                }
            }

            // Notices
            if (state.notice != null) {
                item(key = "notice") {
                    Surface(
                        color = p.reviewContainer,
                        contentColor = p.onReviewContainer,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, p.reviewBorder),
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(state.notice, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (state.partial) {
                item(key = "partial") {
                    Surface(
                        color = p.reviewContainer,
                        contentColor = p.onReviewContainer,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, p.reviewBorder),
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            "A extração ficou incompleta. Confira as páginas sinalizadas no laudo.",
                            Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Section Header + Metadata
            item(key = "section-header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Resultados extraídos",
                        style = MaterialTheme.typography.titleLarge,
                        color = p.title,
                        modifier = Modifier.semantics { heading() },
                    )
                    val pageLabel = if (state.pageCount == 1) "1 página processada" else "${state.pageCount} páginas processadas"
                    Text(
                        "$pageLabel · IA local offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.secondaryText,
                    )
                }
            }

            // Horizontal Status Filter Chips
            if (state.filters.isNotEmpty()) {
                item(key = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.filters.forEach { tab ->
                            ExamesFilterChip(
                                label = "${tab.label} (${tab.count})",
                                isSelected = tab.option == state.activeFilter,
                                onClick = { onSelectFilter(tab.option) },
                            )
                        }
                    }
                }
            }

            // Secondary Management Actions
            item(key = "actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExamesTextButton(onClick = onSelectAnotherPdf) { Text("Selecionar outro PDF") }
                    ExamesTextButton(onClick = { confirmDelete = true }) { Text("Descartar extração") }
                }
            }

            // Filtered empty state
            if (state.pages.isEmpty() && state.totalCount > 0) {
                item(key = "filter-empty") {
                    ExamesCard {
                        Text(
                            "Nenhum item neste filtro.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Toque em Todos para visualizar todos os resultados extraídos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.secondaryText,
                        )
                    }
                }
            }

            // Document-order results grouped by page
            for (page in state.pages) {
                item(key = "page-${page.page}") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Página ${page.page}",
                            style = MaterialTheme.typography.titleMedium,
                            color = p.title,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (page.issueMessage != null) {
                            Surface(
                                color = p.reviewContainer,
                                contentColor = p.onReviewContainer,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, p.reviewBorder),
                                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            ) {
                                Text(page.issueMessage, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                items(page.items, key = { it.id }) { item ->
                    ResultObservationCard(item = item, onOpenEvidence = { onOpenEvidence(item.id) })
                }
            }

            item(key = "disclaimer") {
                Text(
                    "Os dados acima preservam a transcrição do laudo. A interpretação clínica cabe sempre a um profissional de saúde.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.secondaryText,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }

        // Sticky Bottom CTA Bar
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                HorizontalDivider(color = p.decorativeBorder)
                Box(Modifier.padding(horizontal = ScreenMargin, vertical = 12.dp)) {
                    PrimaryAction(
                        text = "Ajuda para conferir o laudo",
                        onClick = onOpenHelp,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteExamConfirmationDialog(
            onConfirm = {
                confirmDelete = false
                onDeleteExam()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ResultObservationCard(
    item: ResultItemUi,
    onOpenEvidence: () -> Unit,
) {
    val p = ExamesTheme.palette
    ExamesCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusChip(label = item.statusLabel, kind = item.statusKind)
        }
        Text(
            item.valueWithUnit,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (item.rawReference != null) {
            Text(
                "Referência do laudo: ${item.rawReference}",
                style = MaterialTheme.typography.bodyMedium,
                color = p.secondaryText,
            )
        }
        Text(
            "${item.documentaryNote} · Pág. ${item.page}",
            style = MaterialTheme.typography.bodySmall,
            color = p.secondaryText,
        )
        ExamesTextButton(
            onClick = onOpenEvidence,
            modifier = Modifier.semantics { contentDescription = "Ver ${item.name} no laudo" },
        ) {
            Icon(ExamesIcons.EyeDocument, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Ver no laudo", style = MaterialTheme.typography.labelLarge)
        }
    }
}
