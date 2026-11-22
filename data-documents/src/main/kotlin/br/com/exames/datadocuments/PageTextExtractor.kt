package br.com.exames.datadocuments

import br.com.exames.domain.model.BoundingBox
import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.SourceRegion

/** Selects the text source, not analytes/references or clinical applicability. */
class PageTextExtractor {
    suspend fun fromPdf(page: PdfPageSnapshot): ExtractedPageText {
        val printable = page.spans.filter { span -> span.raw.any { !it.isWhitespace() && !it.isISOControl() } }
        val usable = printable.isNotEmpty() && printable.all { it.boundingBox != null && '\uFFFD' !in it.raw }
        if (usable) {
            val lines = mutableListOf<SourceText>()
            var text = StringBuilder()
            var box: BoundingBox? = null
            fun flush() {
                if (text.isNotEmpty()) lines += SourceText(page.page, text.toString(), box, null)
                text = StringBuilder(); box = null
            }
            for (span in page.spans) {
                if ('\r' in span.raw || '\n' in span.raw) { flush(); continue }
                text.append(span.raw)
                span.boundingBox?.let { next ->
                    box = box?.let { BoundingBox(minOf(it.left, next.left), minOf(it.top, next.top),
                        maxOf(it.right, next.right), maxOf(it.bottom, next.bottom)) } ?: next
                }
            }
            flush()
            val version="${page.extractorVersion}/words1"
            val fragments = PdfWordFragments.group(page.page,page.spans,page.bitmap.width,page.bitmap.height,version)
            return ExtractedPageText("PDF_TEXT", version, lines, page.rawText, false, fragments)
        }
        val ocr = LocalTextRecognizer().use { it.recognize(page.bitmap, page.page) }
        return ExtractedPageText("OCR", OCR_VERSION, ocr, page.rawText, page.rawText.isNotBlank(),
            ocrFragments(ocr, page.bitmap.width, page.bitmap.height))
    }
    suspend fun fromImage(image: DecodedImage): ExtractedPageText = LocalTextRecognizer().use {
        val ocr = it.recognize(image.bitmap, 1)
        ExtractedPageText("OCR", OCR_VERSION, ocr, null, false,
            ocrFragments(ocr, image.bitmap.width, image.bitmap.height))
    }
    private fun ocrFragments(lines: List<SourceText>, width: Int, height: Int) = lines.flatMapIndexed { lineIndex, line ->
        line.elements.ifEmpty { listOf(line) }.mapIndexed { wordIndex, word ->
            SourceFragment("ocr-${word.page}-$lineIndex-$wordIndex", word.raw,
                region(word.page, word.boundingBox, width, height), "OCR", OCR_VERSION, spaceBefore = true)
        }
    }
    private fun region(page: Int, box: BoundingBox?, width: Int, height: Int): SourceRegion? = box?.takeIf {
        it.left >= 0 && it.top >= 0 && it.right > it.left && it.bottom > it.top && it.right <= width && it.bottom <= height
    }?.let { SourceRegion(page, it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble(), width.toDouble(), height.toDouble()) }
    companion object { const val OCR_VERSION = "tesseract-5.5.1-por-fast-4.1.0-c4932b937207/1" }
}

data class ExtractedPageText(val method: String, val extractorVersion: String,
    val lines: List<SourceText>, val originalEmbeddedText: String?, val embeddedTextRejected: Boolean,
    val fragments: List<SourceFragment> = emptyList())
