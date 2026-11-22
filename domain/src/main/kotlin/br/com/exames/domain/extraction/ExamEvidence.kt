package br.com.exames.domain.extraction

import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.SourceRegion
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable

/** One cell in the upright PDF. The ID and geometry are retained locally across model requests. */
@Serializable
class ExamCitation(val id: String, val text: String, val regions: List<SourceRegion>)

internal class ExamRow(
    val name: ExamCitation,
    val result: ExamCitation,
    val reference: ExamCitation?,
    val referenceUncertain: Boolean,
    val columnUnit: ExamCitation? = null,
)

internal class ExamPageEvidence(val rows: List<ExamRow>, val needsReview: Boolean)

/** Detects the column headers on each page and builds structural evidence independent of the model. */
internal object ExamEvidence {
    private class Line(word: SourceFragment) {
        private val first = checkNotNull(word.region)
        val words = mutableListOf(word)
        var top = first.top
            private set
        var bottom = first.bottom
            private set
        var left = first.left
            private set
        val mid get() = (top + bottom) / 2
        val text get() = join(words)

        fun append(word: SourceFragment) {
            val box = checkNotNull(word.region)
            top = minOf(top, box.top)
            bottom = maxOf(bottom, box.bottom)
            left = minOf(left, box.left)
            words += word
        }
    }

    private class Measurement(val value: List<SourceFragment>, val unit: SourceFragment? = null)
    private class UnitColumns(val relative: SourceFragment, val absolute: SourceFragment)

    private val metadata = Regex(
        """^(data\b|coleta\b|material\b|metodo\b|observac(?:ao|oes)\b|nota\b|referencias?\b|valores?\b|resultado\b|assinatura\b|liberado\b|responsavel\b|paciente\b|pagina\b)"""
    )
    private val absoluteUnit = Regex("""/(?:[µμu]L|mm[³3])""", RegexOption.IGNORE_CASE)
    private val referenceHeading = Regex("""^(?:(?:intervalos?|valores?|tabelas?)\s+(?:de\s+)?referencias?|referencias?)\b""")
    private val administrative = Regex("""^(?:assinado|autenticacao|data\b.*(?:impressao|emissao)|responsavel)\b""")
    private val narrativeReference = Regex("""\b(?:vide|ver|abaixo|conforme)\b|^\s*valores? de referencia""")

    fun prepare(page: Int, fragments: List<SourceFragment>): ExamPageEvidence {
        val words = fragments.filter { it.raw.isNotBlank() && it.region?.page == page }
        if (words.isEmpty() || words.map { it.spanId }.distinct().size != words.size) return ExamPageEvidence(emptyList(), true)
        val bounds = words.first().region!!
        if (words.any { it.region!!.pageWidth != bounds.pageWidth || it.region.pageHeight != bounds.pageHeight }) {
            return ExamPageEvidence(emptyList(), true)
        }
        val lines = lines(words)
        val header = lines.firstOrNull { line ->
            val text = fold(line.text)
            text.startsWith("exame ") && Regex("""\bresultado\b.*\breferencia\b""").containsMatchIn(text)
        } ?: return ExamPageEvidence(emptyList(), true)
        val resultHeader = header.words.firstOrNull { fold(it.raw) == "resultado" }?.region
            ?: return ExamPageEvidence(emptyList(), true)
        val referenceHeader = header.words.firstOrNull { fold(it.raw) == "referencia" }?.region
            ?: return ExamPageEvidence(emptyList(), true)
        val left = header.left
        val width = bounds.pageWidth
        val nameEnd = resultHeader.left - width * 0.018
        val referenceStart = resultHeader.right + (referenceHeader.left - resultHeader.right) * 0.38
        val minCellGap = width * 0.017
        if (left >= nameEnd || referenceStart <= resultHeader.right) return ExamPageEvidence(emptyList(), true)

        val body = lines.dropWhile { it !== header }.drop(1)
            .takeWhile { it.top < bounds.pageHeight * 0.88 }
        val cells = linkedMapOf<Int, Pair<List<SourceFragment>, List<Measurement>>>()
        var units: UnitColumns? = null
        var lastColumnLine: Line? = null
        var uncertain = fragments.any { it.raw.isNotBlank() && it.region == null }
        // Values in the column of current results need a label near the left edge on their own line.
        for ((index, line) in body.withIndex()) {
            val wordsOnLine = line.words.sortedBy { it.region!!.left }
            val names = wordsOnLine.takeWhile { it.region!!.left < nameEnd }
            val result = wordsOnLine.filter { it.region!!.left >= nameEnd && it.region.left < referenceStart }
            val references = wordsOnLine.dropWhile { it.region!!.left < referenceStart }
            if (names.isEmpty() && result.size == 2 && result.first().raw == "%" && absoluteUnit.matches(result.last().raw)) {
                units = UnitColumns(result.first(), result.last())
                lastColumnLine = line
                continue
            }
            val activeUnits = units?.takeIf { lastColumnLine?.let { line.top - it.bottom <= (it.bottom - it.top) * 2 } == true }
            units = null
            val label = join(names)
            if (label.isBlank() || line.left > left + width * 0.055 || metadata.containsMatchIn(fold(label))) continue
            if (result.isEmpty()) continue
            if (result.first().region!!.left - names.last().region!!.right < minCellGap ||
                result.last().region!!.right > referenceStart ||
                references.firstOrNull()?.let { it.region!!.left - result.last().region!!.right < minCellGap } == true) {
                val first = result.first()
                if (first.region!!.left >= resultHeader.left && first.raw.any(Char::isDigit)) uncertain = true
                continue
            }

            val measures = if (activeUnits != null) {
                val split = splitMeasurements(result, activeUnits, minCellGap)
                if (split == null) {
                    uncertain = true
                    continue
                }
                units = activeUnits
                lastColumnLine = line
                split
            } else {
                listOf(Measurement(result))
            }
            cells[index] = names to measures
        }

        val candidates = cells.keys.toList()
        val rows = candidates.flatMapIndexed { position, index ->
            val line = body[index]
            val (names, measures) = cells.getValue(index)
            val right = line.words.sortedBy { it.region!!.left }.dropWhile { it.region!!.left < referenceStart }
            val next = candidates.getOrNull(position + 1) ?: body.size
            val following = body.subList(index + 1, next).takeWhile {
                !administrative.containsMatchIn(fold(it.text))
            }
            val headingLimit = line.bottom + bounds.pageHeight * 0.085
            val nameLines = buildList {
                add(names)
                var previous = line
                for (nextLine in following) {
                    if (kotlin.math.abs(nextLine.left - line.left) > minCellGap / 2 ||
                        nextLine.top - previous.bottom > (previous.bottom - previous.top) * 0.6 ||
                        metadata.containsMatchIn(fold(nextLine.text)) ||
                        nextLine.words.any { it.region!!.right > nameEnd }) break
                    add(nextLine.words)
                    previous = nextLine
                }
            }
            val inline = join(right)
            val placeholder = narrativeReference.containsMatchIn(fold(inline))
            val below = if (placeholder) referenceBelow(following, left, referenceStart, headingLimit) else null
            val inlineReference = listOf(right).takeIf { right.isNotEmpty() && !placeholder }
            val unlabeledContinuation = following.drop(nameLines.size - 1).take(1).any {
                it.left >= referenceStart && it.top - line.bottom <= (line.bottom - line.top) * 2 &&
                    !metadata.containsMatchIn(fold(it.text).trimStart('(', ' '))
            }
            val referenceUncertain = (placeholder && below == null) ||
                (!placeholder && unlabeledContinuation) ||
                (inline.isBlank() && following.any { it.top < headingLimit && referenceHeading.containsMatchIn(fold(it.text)) })

            if (measures.size == 1) {
                val id = "p${page}r${index + 1}"
                listOf(ExamRow(citation("${id}n", nameLines), cite("${id}v", measures.single().value),
                    (below ?: inlineReference)?.takeUnless { referenceUncertain }?.let { citation("${id}f", it) },
                    referenceUncertain = referenceUncertain))
            } else {
                val separate = inlineReference?.takeUnless { referenceUncertain }?.let {
                    splitReferences(right, measures.map { checkNotNull(it.unit).raw })
                }
                measures.mapIndexed { column, measure ->
                    val id = "p${page}r${index + 1}c${column + 1}"
                    ExamRow(citation("${id}n", nameLines), cite("${id}v", measure.value),
                        separate?.get(column)?.let { cite("${id}f", it) },
                        referenceUncertain = referenceUncertain || (right.isNotEmpty() && separate == null),
                        columnUnit = cite("${id}u", listOf(checkNotNull(measure.unit))))
                }
            }
        }
        return ExamPageEvidence(rows, uncertain || rows.isEmpty())
    }

    private fun splitMeasurements(words: List<SourceFragment>, columns: UnitColumns, tolerance: Double): List<Measurement>? {
        val first = columns.relative.region!!
        val second = columns.absolute.region!!
        val boundary = (first.right + second.left) / 2
        val (relative, absolute) = words.partition { it.region!!.right <= boundary }
        if (relative.isEmpty() || absolute.isEmpty() || absolute.any { it.region!!.left < boundary }) return null
        if (kotlin.math.abs(relative.last().region!!.right - first.right) > tolerance ||
            kotlin.math.abs(absolute.last().region!!.right - second.right) > tolerance ||
            !ExamProtocol.isNumeric(join(relative)) || !ExamProtocol.isNumeric(join(absolute))) return null
        return listOf(Measurement(relative, columns.relative), Measurement(absolute, columns.absolute))
    }

    private fun splitReferences(words: List<SourceFragment>, units: List<String>): List<List<SourceFragment>>? {
        val firstEnd = words.indexOfFirst { it.raw == units.first() }
        if (firstEnd < 1 || words.last().raw != units.last() || firstEnd >= words.lastIndex - 1) return null
        val first = words.take(firstEnd + 1)
        val second = words.drop(firstEnd + 1)
        if (!first.any { it.raw.any(Char::isDigit) } || !second.any { it.raw.any(Char::isDigit) }) return null
        return listOf(first, second)
    }

    private fun referenceBelow(lines: List<Line>, left: Double, referenceStart: Double, headingLimit: Double): List<List<SourceFragment>>? {
        val index = lines.indexOfFirst {
            it.top < headingLimit && it.left > left && referenceHeading.containsMatchIn(fold(it.text))
        }
        if (index < 0) return null
        val heading = lines[index]
        val continuation = mutableListOf<Line>()
        var previous = heading
        for (line in lines.drop(index + 1)) {
            if (line.left <= heading.left || line.top - previous.bottom > (previous.bottom - previous.top) * 1.2) break
            if (line.left < referenceStart * 0.58 || line.words.size < 2) break
            continuation += line
            previous = line
        }
        if (continuation.isEmpty() || continuation.none { Regex("""\d""").containsMatchIn(it.text) }) return null
        // Heading qualifiers and all adjacent range lines form a single citation.
        return (listOf(heading) + continuation).map { it.words }
    }

    private fun citation(id: String, lines: List<List<SourceFragment>>) =
        ExamCitation(id, lines.joinToString("\n", transform = ::join), lines.flatten().map { it.region!! })

    private fun cite(id: String, words: List<SourceFragment>): ExamCitation =
        ExamCitation(id, join(words), words.map { it.region!! })

    private fun lines(words: List<SourceFragment>): List<Line> {
        val lines = mutableListOf<Line>()
        for (word in words.sortedBy { (it.region!!.top + it.region.bottom) / 2 }) {
            val region = word.region!!
            val previous = lines.lastOrNull()
            val tolerance = maxOf(region.bottom - region.top, (previous?.let { it.bottom - it.top } ?: 0.0)) * 0.56
            if (previous != null && kotlin.math.abs(previous.mid - (region.top + region.bottom) / 2) <= tolerance) {
                previous.append(word)
            } else {
                lines += Line(word)
            }
        }
        return lines
    }

    private fun join(words: List<SourceFragment>): String = buildString {
        var previous: SourceFragment? = null
        for (word in words.sortedBy { it.region!!.left }) {
            val before = previous
            if (before != null && (word.spaceBefore != false ||
                    word.region!!.left - before.region!!.right > (word.region.bottom - word.region.top) * 0.2)) append(' ')
            append(word.raw.trim())
            previous = word
        }
    }

    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}
