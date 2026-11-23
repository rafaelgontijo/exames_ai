package br.com.exames.coreui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.*
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** Screen 6: Ver no laudo — Meu Exame (dedicated evidence viewer with field highlight toggles). */
@Composable
fun EvidenceScreen(
    state: EvidenceUiState,
    loadPage: suspend (Int) -> Bitmap,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = ExamesTheme.palette
    var showFullPage by remember(state.examId, state.page, state.itemName) { mutableStateOf(false) }
    var showResultHighlight by remember(state.examId, state.page, state.itemName) { mutableStateOf(true) }
    var showReferenceHighlight by remember(state.examId, state.page, state.itemName) { mutableStateOf(true) }
    var bitmap by remember(state.examId, state.page) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(state.examId, state.page) { mutableStateOf(false) }

    LaunchedEffect(state.examId, state.page) {
        bitmap = null
        failed = false
        try {
            bitmap = loadPage(state.page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    val loadedImage = bitmap
    DisposableEffect(loadedImage) {
        onDispose { loadedImage?.recycle() }
    }

    val activeResultBoxes = if (showResultHighlight) state.resultBoxes else emptyList()
    val activeReferenceBoxes = if (showReferenceHighlight) state.referenceBoxes else emptyList()
    val allActiveBoxes = activeResultBoxes + activeReferenceBoxes
    val hasValidGeometry = allActiveBoxes.isNotEmpty() && allActiveBoxes.all { isValidBox(it) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ExamesTopBar(
            title = "Ver no laudo",
            onBack = onBack,
            trailing = {
                StatusChip(
                    label = "Página ${state.page} de ${state.totalPages.coerceAtLeast(state.page)}",
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Selected Measurement Context Card
            ExamesCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        state.itemName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(label = state.statusLabel, kind = state.statusKind)
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        state.valueWithUnit,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Valor extraído",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.secondaryText,
                    )
                }
                if (state.rawReference != null) {
                    ExamesInfoBox {
                        Text(
                            "Referência preservada do laudo:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = p.onInfoContainer,
                        )
                        Text(
                            state.rawReference,
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.onInfoContainer,
                        )
                    }
                }
                Text(state.documentaryNote, style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            }

            // View mode & field highlight toggles
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExamesFilterChip(
                    label = "Recorte",
                    isSelected = !showFullPage,
                    onClick = { showFullPage = false },
                )
                ExamesFilterChip(
                    label = "Página inteira",
                    isSelected = showFullPage,
                    onClick = { showFullPage = true },
                )
                ExamesFilterChip(
                    label = "Resultado",
                    isSelected = showResultHighlight,
                    onClick = { showResultHighlight = !showResultHighlight },
                )
                if (state.referenceBoxes.isNotEmpty()) {
                    ExamesFilterChip(
                        label = "Referência",
                        isSelected = showReferenceHighlight,
                        onClick = { showReferenceHighlight = !showReferenceHighlight },
                    )
                }
            }

            // Original PDF Page Preview
            ExamesCard {
                when {
                    failed -> {
                        Text(
                            "Não foi possível abrir a página do laudo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.onAlertContainer,
                        )
                    }
                    loadedImage != null -> {
                        if (allActiveBoxes.isNotEmpty() && !hasValidGeometry) {
                            Surface(
                                color = p.reviewContainer,
                                contentColor = p.onReviewContainer,
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, p.reviewBorder),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "As coordenadas do trecho não puderam ser projetadas com precisão; a página inteira permanece visível para conferência.",
                                    Modifier.padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        EvidencePageCanvas(
                            bitmap = loadedImage,
                            page = state.page,
                            resultBoxes = if (hasValidGeometry) activeResultBoxes else emptyList(),
                            referenceBoxes = if (hasValidGeometry) activeReferenceBoxes else emptyList(),
                            showFullPage = showFullPage || !hasValidGeometry,
                            resultColor = MaterialTheme.colorScheme.primary,
                            referenceColor = p.onReviewContainer,
                        )
                        Text(
                            "Trechos destacados conforme a posição original no PDF",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.secondaryText,
                        )
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Carregando página ${state.page}...", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                        }
                    }
                }
            }
        }

        // Sticky Bottom Bar
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                HorizontalDivider(color = p.decorativeBorder)
                Column(
                    modifier = Modifier.padding(horizontal = ScreenMargin, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "A conferência visual não substitui a avaliação de um profissional de saúde.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.secondaryText,
                    )
                    PrimaryAction(text = "Voltar aos resultados", onClick = onBack)
                }
            }
        }
    }
}

internal fun isValidBox(box: EvidenceBoxUi): Boolean =
    box.pageWidth > 0f &&
        box.pageHeight > 0f &&
        box.left >= -2f &&
        box.top >= -2f &&
        box.right <= box.pageWidth + 2f &&
        box.bottom <= box.pageHeight + 2f &&
        box.right - box.left > 0.5f &&
        box.bottom - box.top > 0.5f

@Composable
private fun EvidencePageCanvas(
    bitmap: Bitmap,
    page: Int,
    resultBoxes: List<EvidenceBoxUi>,
    referenceBoxes: List<EvidenceBoxUi>,
    showFullPage: Boolean,
    resultColor: Color,
    referenceColor: Color,
) {
    val all = resultBoxes + referenceBoxes
    val cropTop: Int
    val cropHeight: Int
    if (showFullPage || all.isEmpty()) {
        cropTop = 0
        cropHeight = bitmap.height.coerceAtLeast(1)
    } else {
        val pageHeight = all.first().pageHeight
        val minTop = (all.minOf { it.top } / pageHeight * bitmap.height).toInt()
        val maxBottom = (all.maxOf { it.bottom } / pageHeight * bitmap.height).roundToInt()
        val pad = (bitmap.height * 0.05f).toInt()
        cropTop = (minTop - pad).coerceAtLeast(0)
        val cropBottom = (maxBottom + pad).coerceAtMost(bitmap.height)
        cropHeight = (cropBottom - cropTop).coerceAtLeast(1)
    }

    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val description = if (showFullPage) {
        "Página $page inteira com destaque nos trechos confirmados"
    } else {
        "Recorte da página $page com destaque nos trechos confirmados"
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / cropHeight.toFloat())
            .semantics { contentDescription = description },
    ) {
        drawImage(
            image = image,
            srcOffset = IntOffset(0, cropTop),
            srcSize = IntSize(bitmap.width, cropHeight),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
        if (all.isEmpty()) return@Canvas
        val pageWidth = all.first().pageWidth
        val pageHeight = all.first().pageHeight
        val scaleX = size.width / pageWidth
        val scaleY = size.height / cropHeight * bitmap.height / pageHeight
        val cropOffset = cropTop.toFloat() / cropHeight * size.height

        fun drawBox(box: EvidenceBoxUi, strokeColor: Color) {
            val left = (box.left * scaleX).coerceIn(0f, size.width)
            val top = (box.top * scaleY - cropOffset).coerceIn(0f, size.height)
            val right = (box.right * scaleX).coerceIn(left, size.width)
            val bottom = (box.bottom * scaleY - cropOffset).coerceIn(top, size.height)
            val w = right - left
            val h = bottom - top
            if (w <= 0f || h <= 0f) return
            drawRect(
                color = strokeColor.copy(alpha = 0.16f),
                topLeft = Offset(left, top),
                size = Size(w, h),
            )
            drawRect(
                color = strokeColor,
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        resultBoxes.forEach { drawBox(it, resultColor) }
        referenceBoxes.forEach { drawBox(it, referenceColor) }
    }
}
