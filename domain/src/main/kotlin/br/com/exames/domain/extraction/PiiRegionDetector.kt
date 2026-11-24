package br.com.exames.domain.extraction

import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.SourceRegion
import java.text.Normalizer
import java.util.Locale

/**
 * Pure JVM detector of explicit PII and patient-identification header regions on positioned digital
 * PDF text, used strictly in memory to paint opaque redaction boxes before dispatching band images
 * to the opt-in private cloud fallback.
 */
object PiiRegionDetector {
    private val cpfFormatted = Regex("""\b\d{3}\.\d{3}\.\d{3}-\d{2}\b""")
    private val cpfDigits11 = Regex("""\b\d{11}\b""")
    private val emailRegex = Regex("""[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}""")
    private val phoneRegex = Regex("""(?:\+?55\s*)?(?:\(\d{2}\)|\b\d{2})\s*(?:9\d{4}|\d{4})[-\s]?\d{4}\b|\b9\d{4}-\d{4}\b""")
    private val dateRegex = Regex("""\b\d{2}/\d{2}/\d{2,4}\b""")

    private val labUnitOrMeasurement = Regex(
        """(?:\b(?:mg|g|ug|ng|pg|mmol|meq|ui|mui|u|ml|dl|ul|fl|mm3|mm³|milhoes)\s*/\s*(?:dl|ml|l|ul|mm3|mm³|min)|\bresultado\s*:|\bvalor(?:es)?\s+de\s+referencia\b|\b\d+(?:[.,]\d+)?\s*(?:%|fl|pg)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val adminLabels = listOf(
        "nome do paciente",
        "nome da mae",
        "data de nascimento",
        "data nascimento",
        "medico solicitante",
        "cartao sus",
        "paciente",
        "nome",
        "cpf",
        "rg",
        "cns",
        "carteirinha",
        "prontuario",
        "atendimento",
        "requisicao",
        "nascimento",
        "dn",
        "d.n.",
        "mae",
        "convenio",
        "plano",
        "solicitante",
        "crm",
        "endereco",
        "telefone",
        "celular",
        "e-mail",
        "email",
    )

    private class Line(first: SourceFragment) {
        val words = mutableListOf(first)
        var top = first.region!!.top
            private set
        var bottom = first.region!!.bottom
            private set
        val mid get() = (top + bottom) / 2.0

        fun append(word: SourceFragment) {
            val box = word.region!!
            top = minOf(top, box.top)
            bottom = maxOf(bottom, box.bottom)
            words += word
        }

        val sortedWords: List<SourceFragment> by lazy { words.sortedBy { it.region!!.left } }
        val foldedText: String by lazy { fold(sortedWords.joinToString(" ") { it.raw.trim() }) }
    }

    fun detect(fragments: List<SourceFragment>): List<SourceRegion> {
        val positioned = fragments.filter { it.raw.isNotBlank() && it.region != null }
        if (positioned.isEmpty()) return emptyList()

        val lines = groupLines(positioned)
        val regions = linkedSetOf<SourceRegion>()

        for (line in lines) {
            val lineText = line.foldedText
            val isLabLine = labUnitOrMeasurement.containsMatchIn(lineText)

            // 1. Always redact explicit PII tokens (CPF, email, phone) even if they share a line.
            redactExplicitPatterns(line, regions)

            // 2. Redact administrative patient-identity field values on non-measurement lines.
            if (!isLabLine) {
                redactAdministrativeHeaderValues(line, regions)
            }
        }
        return regions.toList()
    }

    private fun redactExplicitPatterns(line: Line, out: MutableSet<SourceRegion>) {
        val words = line.sortedWords
        val lineHasCpfLabel = line.foldedText.contains("cpf")
        val lineHasPhoneLabel = line.foldedText.contains("tel") || line.foldedText.contains("cel") || line.foldedText.contains("fone")

        // Check single words and contiguous spans up to 3 words (e.g., "(11)", "99999-8888").
        for (i in words.indices) {
            val wf = fold(words[i].raw)
            if (cpfFormatted.containsMatchIn(wf) ||
                emailRegex.containsMatchIn(wf) ||
                (lineHasCpfLabel && cpfDigits11.containsMatchIn(wf))
            ) {
                words[i].region?.let(out::add)
            }
            if (!dateRegex.containsMatchIn(wf) && phoneRegex.containsMatchIn(wf)) {
                words[i].region?.let(out::add)
            }
            for (span in 2..minOf(3, words.size - i)) {
                val sub = words.subList(i, i + span)
                val joined = fold(sub.joinToString(" ") { it.raw.trim() })
                if (cpfFormatted.containsMatchIn(joined) || emailRegex.containsMatchIn(joined)) {
                    mergeRegions(sub)?.let(out::add)
                } else if (!dateRegex.containsMatchIn(joined) && (phoneRegex.containsMatchIn(joined) || (lineHasPhoneLabel && Regex("""\(\d{2}\)\s*\d{4,5}-?\d{4}""").containsMatchIn(joined)))) {
                    mergeRegions(sub)?.let(out::add)
                }
            }
        }
    }

    private fun redactAdministrativeHeaderValues(line: Line, out: MutableSet<SourceRegion>) {
        val words = line.sortedWords
        var i = 0
        while (i < words.size) {
            val matchedSpan = matchAdminLabelAt(words, i)
            if (matchedSpan > 0) {
                val valueStart = i + matchedSpan
                var valueEnd = valueStart
                while (valueEnd < words.size) {
                    if (matchAdminLabelAt(words, valueEnd) > 0) break
                    val raw = words[valueEnd].raw.trim()
                    if (raw == "|" || raw == "/") {
                        // Pipe separates header columns; stop at pipe.
                        if (raw == "|") break
                    }
                    valueEnd++
                }
                if (valueEnd > valueStart) {
                    mergeRegions(words.subList(valueStart, valueEnd))?.let(out::add)
                }
                i = valueEnd
            } else {
                i++
            }
        }
    }

    private fun matchAdminLabelAt(words: List<SourceFragment>, start: Int): Int {
        // Try 3-word, 2-word, then 1-word label ending with ':' (or exact known label before a ':' token).
        for (len in minOf(3, words.size - start) downTo 1) {
            val slice = words.subList(start, start + len)
            val joined = fold(slice.joinToString(" ") { it.raw.trim() })
            val hasColonAtEnd = joined.endsWith(":")
            val nextIsColon = start + len < words.size && words[start + len].raw.trim() == ":"
            if (!hasColonAtEnd && !nextIsColon) continue
            val normalizedLabel = joined.removeSuffix(":").trim()
            if (normalizedLabel in adminLabels) {
                return if (hasColonAtEnd) len else len + 1
            }
        }
        return 0
    }

    private fun mergeRegions(words: List<SourceFragment>): SourceRegion? {
        val boxes = words.mapNotNull { it.region }
        if (boxes.isEmpty()) return null
        val first = boxes.first()
        return SourceRegion(
            page = first.page,
            left = boxes.minOf { it.left },
            top = boxes.minOf { it.top },
            right = boxes.maxOf { it.right },
            bottom = boxes.maxOf { it.bottom },
            pageWidth = first.pageWidth,
            pageHeight = first.pageHeight,
        )
    }

    private fun groupLines(words: List<SourceFragment>): List<Line> {
        val lines = mutableListOf<Line>()
        for (word in words.sortedBy { (it.region!!.top + it.region.bottom) / 2.0 }) {
            val region = word.region!!
            val previous = lines.lastOrNull()
            val tolerance = maxOf(
                region.bottom - region.top,
                previous?.let { it.bottom - it.top } ?: 0.0,
            ) * 0.56
            if (previous != null && kotlin.math.abs(previous.mid - (region.top + region.bottom) / 2.0) <= tolerance) {
                previous.append(word)
            } else {
                lines += Line(word)
            }
        }
        return lines
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
}
