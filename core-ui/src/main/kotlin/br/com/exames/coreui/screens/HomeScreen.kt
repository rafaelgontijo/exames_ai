package br.com.exames.coreui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.*

/** Screen 1: Início — Meu Exame. */
@Composable
fun HomeScreen(
    recentExams: List<ExamCardItemUi>,
    notice: String?,
    onSelectPdf: () -> Unit,
    onOpenSend: () -> Unit,
    onOpenExam: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenHelp: () -> Unit,
    onDeleteExam: (String) -> Unit,
    modifier: Modifier = Modifier,
    clinicalContext: ClinicalContextUi = ClinicalContextUi(),
    onUpdateContext: (ClinicalContextUi) -> Unit = {},
    onClearContext: () -> Unit = {},
) {
    val p = ExamesTheme.palette
    val fontScale = LocalDensity.current.fontScale
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenMargin, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Top Brand Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ExamesIcons.Flask, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                }
                Text(
                    "Meu Exame",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = p.title,
                )
            }
            OutlinedIconButton(
                onClick = onOpenHelp,
                modifier = Modifier.size(MinTouchTarget),
                shape = CircleShape,
                border = BorderStroke(1.dp, p.controlBorder),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(ExamesIcons.HelpCircle, contentDescription = "Ajuda", modifier = Modifier.size(22.dp))
            }
        }

        // Greeting
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Olá! Vamos conferir o seu exame?",
                style = MaterialTheme.typography.headlineMedium,
                color = p.title,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Selecione o PDF digital do laudo para extrair os resultados no seu aparelho.",
                style = MaterialTheme.typography.bodyLarge,
                color = p.secondaryText,
            )
        }

        // Operational notice (e.g., cancelled, discarded, error)
        if (notice != null) {
            Surface(
                color = p.reviewContainer,
                contentColor = p.onReviewContainer,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, p.reviewBorder),
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(notice, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Ephemeral Pre-Import Clinical Context Card (never persisted)
        SessionContextCard(
            context = clinicalContext,
            onUpdate = onUpdateContext,
            onClear = onClearContext,
            initiallyExpanded = false,
            titleWhenEmpty = "Dados básicos para a leitura (opcional)",
            subtitleWhenEmpty = "Informe sexo, idade ou jejum antes de selecionar o PDF. Não fica salvo.",
        )

        // Primary Hero Card
        ExamesHeroCard(
            title = "Importar um exame",
            subtitle = "PDF digital · 100% offline no aparelho",
            icon = ExamesIcons.Document,
            ctaText = "Selecionar PDF",
            onCtaClick = onSelectPdf,
        )

        // Secondary link to dedicated Send screen with tips & privacy details
        SecondaryAction(
            text = "Opções de envio e dicas",
            onClick = onOpenSend,
        )

        // 3-Step Guide (adapts to column under high font scale so labels never clip)
        if (fontScale >= 1.5f) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExamesStepCard(1, "Selecione o PDF", Modifier.fillMaxWidth())
                ExamesStepCard(2, "Confira cada resultado", Modifier.fillMaxWidth())
                ExamesStepCard(3, "Veja o trecho no laudo", Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ExamesStepCard(1, "Selecione o PDF", Modifier.weight(1f))
                ExamesStepCard(2, "Confira cada resultado", Modifier.weight(1f))
                ExamesStepCard(3, "Veja o trecho no laudo", Modifier.weight(1f))
            }
        }

        // Recent Exams Section
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Meus exames",
                    style = MaterialTheme.typography.titleLarge,
                    color = p.title,
                    modifier = Modifier.semantics { heading() },
                )
                if (recentExams.isNotEmpty()) {
                    ExamesTextButton(onClick = onOpenHistory) {
                        Text("Ver todos", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (recentExams.isEmpty()) {
                ExamesCard {
                    Text(
                        "Nenhum exame salvo neste aparelho ainda.",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Os PDFs importados ficam privados e criptografados somente neste celular.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.secondaryText,
                    )
                }
            } else {
                recentExams.take(3).forEach { exam ->
                    ExamHistoryRowCard(
                        exam = exam,
                        onOpen = { onOpenExam(exam.id) },
                        onRequestDelete = { pendingDeleteId = exam.id },
                    )
                }
            }
        }
    }

    pendingDeleteId?.let { idToDelete ->
        DeleteExamConfirmationDialog(
            onConfirm = {
                pendingDeleteId = null
                onDeleteExam(idToDelete)
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}

/** Tab 2: Exames — full local encrypted history. */
@Composable
fun ExamsHistoryScreen(
    exams: List<ExamCardItemUi>,
    notice: String?,
    onOpenExam: (String) -> Unit,
    onDeleteExam: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onOpenSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = ExamesTheme.palette
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenMargin, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Meus exames",
            style = MaterialTheme.typography.headlineMedium,
            color = p.title,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Histórico privado e cifrado salvo somente neste aparelho.",
            style = MaterialTheme.typography.bodyLarge,
            color = p.secondaryText,
        )

        if (notice != null) {
            Surface(
                color = p.reviewContainer,
                contentColor = p.onReviewContainer,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, p.reviewBorder),
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(notice, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        PrimaryAction(text = "Importar novo PDF", onClick = onOpenSend)

        if (exams.isEmpty()) {
            ExamesCard {
                Text(
                    "Nenhum exame salvo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Toque em Importar novo PDF para extrair resultados offline com IA no aparelho.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.secondaryText,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                exams.forEach { exam ->
                    ExamHistoryRowCard(
                        exam = exam,
                        onOpen = { onOpenExam(exam.id) },
                        onRequestDelete = { pendingDeleteId = exam.id },
                    )
                }
            }
            SecondaryAction(
                text = "Apagar todos os exames",
                onClick = { confirmClearAll = true },
            )
        }
    }

    pendingDeleteId?.let { idToDelete ->
        DeleteExamConfirmationDialog(
            onConfirm = {
                pendingDeleteId = null
                onDeleteExam(idToDelete)
            },
            onDismiss = { pendingDeleteId = null },
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Apagar todos os exames?") },
            text = { Text("Todos os PDFs e resultados salvos pelo Meu Exame serão removidos definitivamente deste aparelho.") },
            confirmButton = {
                ExamesTextButton(onClick = {
                    confirmClearAll = false
                    onDeleteAll()
                }) { Text("Apagar tudo") }
            },
            dismissButton = {
                ExamesTextButton(onClick = { confirmClearAll = false }) { Text("Voltar") }
            },
        )
    }
}

@Composable
internal fun ExamHistoryRowCard(
    exam: ExamCardItemUi,
    onOpen: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val p = ExamesTheme.palette
    ExamesCard(
        onClick = onOpen,
        borderedControl = true,
        modifier = Modifier.semantics {
            contentDescription = "Abrir ${exam.title}, ${exam.subtitleDate}, ${exam.statusLabel}"
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(exam.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(exam.subtitleDate, style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                StatusChip(label = exam.statusLabel, kind = exam.statusKind)
            }
            IconButton(
                onClick = onRequestDelete,
                modifier = Modifier.size(MinTouchTarget),
            ) {
                Icon(
                    ExamesIcons.Trash,
                    contentDescription = "Excluir ${exam.title}",
                    tint = p.secondaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun DeleteExamConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Excluir exame?") },
        text = { Text("O PDF original e os resultados desta extração serão removidos definitivamente deste aparelho.") },
        confirmButton = { ExamesTextButton(onClick = onConfirm) { Text("Descartar") } },
        dismissButton = { ExamesTextButton(onClick = onDismiss) { Text("Voltar") } },
    )
}
