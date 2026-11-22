package br.com.exames.featureresults

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun EvidenceSourcePreview(bitmap: Bitmap, page: Int, selection: SelectedEvidence?) {
    val projected = selection?.evidence?.map { EvidenceProjection.project(it.region, bitmap.width, bitmap.height) }
    val compatible = projected != null && projected.isNotEmpty() && projected.all { it != null } &&
        selection?.evidence?.all { it.region.page == page } == true
    selection?.let {
        Text(it.label)
        if (it.raw != null) Text("Texto bruto do campo: ${it.raw}")
        else Text("Trechos brutos citados: " + it.evidence.joinToString(" | ") { span -> span.raw })
        if (compatible) Text("${projected!!.size} regiões deste campo destacadas na página $page.")
        else Text("Regiões indisponíveis ou incompatíveis com esta prévia. Nenhum destaque foi estimado.")
    }
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).clipToBounds().testTag("private-preview")) {
        Image(bitmap.asImageBitmap(), "Página $page da cópia privada", Modifier.matchParentSize())
        if (compatible) Canvas(Modifier.matchParentSize().testTag("evidence-overlay").semantics {
            contentDescription = "${projected!!.size} regiões destacadas na página $page"
        }) {
            for (box in projected!!.filterNotNull()) {
                val offset = Offset(box.left * size.width, box.top * size.height)
                val extent = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height)
                drawRect(Color(0xFFFFC107).copy(alpha = 0.18f), offset, extent)
                drawRect(Color(0xFF005EA8), offset, extent, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
