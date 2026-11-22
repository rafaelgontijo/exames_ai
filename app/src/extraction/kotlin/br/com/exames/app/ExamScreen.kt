package br.com.exames.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.com.exames.domain.extraction.ExamIssue
import br.com.exames.domain.extraction.ExamObservation
import br.com.exames.domain.inference.InferenceFailure
import br.com.exames.domain.validation.SourceRegion
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

@Composable
internal fun ExamScreen(
    state: ExamScreenState,
    select: () -> Unit,
    cancel: () -> Unit,
    discard: () -> Unit,
    preview: suspend (String, Int) -> Bitmap,
) {
    var confirmation by remember(state) { mutableStateOf<Action?>(null) }
    var evidence by remember(state) { mutableStateOf<ExamObservation?>(null) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Meu Exame", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        when (state) {
                            is ExamScreenState.Entry -> "Extrair resultados"
                            is ExamScreenState.Working -> state.message
                            is ExamScreenState.Finished -> "Resultados extraídos"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
            when (state) {
                is ExamScreenState.Entry -> {
                    item {
                        Text("Selecione um PDF digital de exame. A IA processa o arquivo no aparelho, sem internet.")
                    }
                    state.notice?.let { item { Notice(it) } }
                    item { Button(onClick = select, modifier = Modifier.fillMaxWidth()) { Text("Selecionar PDF") } }
                    if (state.canDiscard) item {
                        TextButton(onClick = { confirmation = Action.DISCARD }) { Text("Descartar extração") }
                    }
                }
                is ExamScreenState.Working -> {
                    item {
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.pages > 0) Text("Página ${state.page} de ${state.pages}")
                            if (state.blocks > 0) Text("Bloco ${state.block} de ${state.blocks}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (state.cancellable) item { TextButton(onClick = cancel) { Text("Cancelar") } }
                }
                is ExamScreenState.Finished -> {
                    val result = state.extraction
                    val count = result.pages.sumOf { it.observations.size }
                    item {
                        Text(
                            when (count) {
                                0 -> "Nenhum resultado confirmado pela IA."
                                1 -> "1 resultado com evidência no laudo"
                                else -> "$count resultados com evidência no laudo"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    state.notice?.let { item { Notice(it) } }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { confirmation = Action.REPLACE }) { Text("Selecionar outro PDF") }
                            TextButton(onClick = { confirmation = Action.DISCARD }) { Text("Descartar extração") }
                        }
                    }
                    if (result.partial) item { Notice("A extração ficou incompleta. Confira as páginas sinalizadas no laudo.") }
                    for (page in result.pages) {
                        item(key = "page-${page.page}") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Página ${page.page}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                if (page.issues.isNotEmpty()) Notice(pageNotice(page.issues, page.inferenceFailures))
                            }
                        }
                        items(page.observations, key = { it.sourceResult.id }) { row ->
                            ResultCard(row) { evidence = row }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (action == Action.REPLACE) "Selecionar outro PDF?" else "Descartar extração?") },
            text = { Text(if (action == Action.REPLACE) "Um novo PDF substituirá o arquivo e os resultados atuais."
                else "O PDF e os resultados desta extração serão removidos do aplicativo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    if (action == Action.REPLACE) select() else discard()
                }) { Text(if (action == Action.REPLACE) "Selecionar PDF" else "Descartar") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Voltar") } },
        )
    }
    if (state is ExamScreenState.Finished) evidence?.let { row ->
        SourceDialog(row, load = { page -> preview(state.id, page) }, close = { evidence = null })
    }
}

private enum class Action { DISCARD, REPLACE }

@Composable
private fun ResultCard(row: ExamObservation, viewSource: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(row.name.text, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(row.result, row.unit).joinToString(" "), style = MaterialTheme.typography.headlineSmall)
            row.reference?.let {
                Text("Referência no laudo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (row.referenceNeedsReview) Text("Confira a referência no laudo.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = viewSource, modifier = Modifier.semantics {
                contentDescription = "Ver ${row.name.text} no laudo"
            }) { Text("Ver no laudo") }
        }
    }
}

@Composable
private fun Notice(message: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun pageNotice(issues: Set<ExamIssue>, failures: Set<InferenceFailure>): String = when {
    InferenceFailure.UNSUPPORTED_DEVICE in failures -> "O aparelho não oferece suporte ao modelo de IA."
    InferenceFailure.MODEL_SPACE in failures -> "Espaço insuficiente para preparar o modelo de IA."
    ExamIssue.PAGE_UNREADABLE in issues -> "Não foi possível ler esta página."
    ExamIssue.INSUFFICIENT_POSITIONED_TEXT in issues -> "Confira o texto e os resultados desta página no laudo."
    ExamIssue.INFERENCE_FAILED in issues || ExamIssue.INVALID_RESPONSE in issues -> "A IA não concluiu todos os trechos desta página."
    ExamIssue.INPUT_LIMIT in issues -> "Um trecho desta página excedeu o limite da IA."
    else -> "Confira os campos e possíveis omissões nesta página."
}

@Composable
private fun SourceDialog(row: ExamObservation, load: suspend (Int) -> Bitmap, close: () -> Unit) {
    val regions = row.name.regions + row.sourceResult.regions + row.unitCitation?.regions.orEmpty() + row.reference?.regions.orEmpty()
    val page = regions.first().page
    var showPage by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(page) {
        try {
            bitmap = load(page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val image = bitmap
    DisposableEffect(image) { onDispose { image?.recycle() } }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Página $page do laudo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    failed -> Text("Não foi possível abrir a página.")
                    image != null -> {
                        Text("Os contornos destacam os campos vinculados ao resultado.")
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showPage = !showPage }) {
                            Text(if (showPage) "Mostrar só o trecho" else "Mostrar página inteira")
                        }
                        EvidenceImage(image, regions, showPage)
                    }
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Fechar") } },
    )
}

@Composable
private fun EvidenceImage(bitmap: Bitmap, regions: List<SourceRegion>, showPage: Boolean) {
    val page = regions.first()
    val top = (regions.minOf { it.top } / page.pageHeight * bitmap.height).toInt()
    val bottom = (regions.maxOf { it.bottom } / page.pageHeight * bitmap.height).roundToInt()
    val padding = (bitmap.height * 0.035).toInt()
    val cropTop = if (showPage) 0 else (top - padding).coerceAtLeast(0)
    val cropBottom = if (showPage) bitmap.height else (bottom + padding).coerceAtMost(bitmap.height)
    val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / cropHeight).semantics {
        contentDescription = if (showPage) "Página ${page.page} inteira com destaque nos trechos confirmados"
            else "Recorte da página ${page.page} com destaque nos trechos confirmados"
    }) {
        drawImage(image, srcOffset = IntOffset(0, cropTop), srcSize = IntSize(bitmap.width, cropHeight),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        val x = size.width / page.pageWidth.toFloat()
        val y = size.height / cropHeight * bitmap.height / page.pageHeight.toFloat()
        val cropOffset = cropTop.toFloat() / cropHeight * size.height
        for (region in regions) {
            drawRect(Color(0xff0c6558), Offset(region.left.toFloat() * x, region.top.toFloat() * y - cropOffset),
                Size((region.right - region.left).toFloat() * x, (region.bottom - region.top).toFloat() * y),
                style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
