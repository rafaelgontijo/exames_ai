package br.com.exames.datadocuments

import br.com.exames.domain.model.BoundingBox
import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.SourceRegion

/** Coalesces adjacent glyphs only; explicit whitespace and geometry delimit groups, never guessed values. */
internal object PdfWordFragments {
    fun group(page: Int,spans: List<PdfTextSpan>,width: Int,height: Int,version: String): List<SourceFragment> {
        require(page>=1 && width>0 && height>0)
        val output=mutableListOf<SourceFragment>();val pending=mutableListOf<PdfTextSpan>()
        var whitespace=false;var pendingSpace=false
        fun emit(raw: String,id: String,box: BoundingBox?,space: Boolean) {
            val region=box?.takeIf { it.left>=0 && it.top>=0 && it.right>it.left && it.bottom>it.top && it.right<=width && it.bottom<=height }
                ?.let { SourceRegion(page,it.left.toDouble(),it.top.toDouble(),it.right.toDouble(),it.bottom.toDouble(),width.toDouble(),height.toDouble()) }
            output+=SourceFragment(id,raw,region,"PDF_TEXT",version,spaceBefore=space)
        }
        fun flush() {
            if(pending.isEmpty()) return
            val boxes=pending.map { requireNotNull(it.boundingBox) }
            emit(pending.joinToString("") { it.raw },"pdfword-$page-${pending.first().sourceIndex}-${pending.last().sourceIndex}",
                BoundingBox(boxes.minOf { it.left },boxes.minOf { it.top },boxes.maxOf { it.right },boxes.maxOf { it.bottom }),pendingSpace)
            pending.clear()
        }
        for(span in spans) {
            if(span.raw.isEmpty()) continue
            if(span.raw.all { it.isWhitespace() }) { flush();whitespace=true;continue }
            val box=span.boundingBox
            if(box==null || span.raw.any { it.isWhitespace() || it.isISOControl() }) {
                flush();emit(span.raw,span.spanId,box,whitespace);whitespace=span.raw.last().isWhitespace();continue
            }
            val previous=pending.lastOrNull()?.boundingBox
            if(previous!=null && (box.left<previous.left || box.left-previous.right>maxOf(2,previous.bottom-previous.top) ||
                    minOf(box.bottom,previous.bottom)<=maxOf(box.top,previous.top))) flush()
            if(pending.isEmpty()) pendingSpace=whitespace
            pending+=span;whitespace=false
        }
        flush();return output
    }
}
