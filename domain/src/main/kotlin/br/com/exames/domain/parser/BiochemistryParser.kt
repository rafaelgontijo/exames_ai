package br.com.exames.domain.parser

import br.com.exames.domain.evaluator.ConservativeNumberParser
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** Document text only: no expected answers, external ranges, Android objects or previous session. */
data class SourceFragment(val spanId: String, val raw: String, val region: SourceRegion?,
    val method: String, val extractorVersion: String, val confidence: Double? = null,
    /** Explicit source boundary (PDF characters / OCR words); null for legacy positioned spans. */
    val spaceBefore: Boolean? = null)
enum class SupportedLayout(val id: String) { SIMPLE("simple-v1"), FOOTNOTE("footnote-v1") }
enum class ParseWarning { UNSUPPORTED_LAYOUT, UNSUPPORTED_ANALYTE, UNPOSITIONED_CONTENT, COMPETING_ASSOCIATIONS, DUPLICATE_ANALYTE }
data class ParsedObservation(val observation: Observation, val referenceForAssessment: ReferenceRange?)
data class ParsedPage(val layout: SupportedLayout?, val observations: List<ParsedObservation>,
    val warnings: Set<ParseWarning>, val unsupported: List<SourceFragment>)

/** Two explicitly versioned internal table layouts, with conservative association. */
class BiochemistryParser {
    private val numbers = ConservativeNumberParser()
    fun parse(documentId: String, runId: String, page: Int, fragments: List<SourceFragment>): ParsedPage {
        require(page >= 1 && documentId.isNotBlank() && runId.isNotBlank())
        require(fragments.map { it.spanId }.toSet().size == fragments.size && fragments.all { it.spanId.isNotBlank() })
        require(fragments.all { it.region == null || it.region.page == page })
        require(fragments.mapNotNull { it.region?.let { r -> r.pageWidth to r.pageHeight } }.toSet().size <= 1)
        val warnings = mutableSetOf<ParseWarning>()
        if (fragments.any { it.raw.isNotBlank() && it.region == null }) warnings += ParseWarning.UNPOSITIONED_CONTENT
        val rows = rows(fragments.filter { it.region != null && it.raw.isNotBlank() })
        val headers = rows.filter { row ->
            val text = fold(join(row))
            val positions = listOf("analito", "resultado", "unidade", "referencia").map { text.indexOf(it) }
            positions.all { it >= 0 } && positions.zipWithNext().all { (a, b) -> a < b }
        }
        val declared = rows.flatMap { Regex("layout:\\s*([a-z0-9-]+)").findAll(fold(join(it))).map { m -> m.groupValues[1] } }.distinct()
        val hasNoteLinks = rows.any { Regex("ver nota\\s+[0-9]+").containsMatchIn(fold(join(it))) }
        val layout = if (headers.size == 1 && declared.size <= 1) {
            when {
                declared.singleOrNull()?.let { it !in setOf("simple-v1", "footnote-v1") } == true -> null
                declared.singleOrNull() == "simple-v1" && hasNoteLinks -> null
                declared.singleOrNull() == "footnote-v1" || hasNoteLinks -> SupportedLayout.FOOTNOTE
                declared.singleOrNull() == "simple-v1" || declared.isEmpty() -> SupportedLayout.SIMPLE
                else -> null
            }
        } else null
        if (layout == null) warnings += ParseWarning.UNSUPPORTED_LAYOUT
        val headerBottom = headers.singleOrNull()?.maxOf { it.region!!.bottom } ?: Double.POSITIVE_INFINITY
        val contextRows = rows.filter { Regex("^contexto:\\s*(.+)$").matches(fold(join(it))) }
        val contexts = contextRows.mapNotNull { row ->
            Regex("^contexto:\\s*(.+)$").matchEntire(fold(join(row)))?.groupValues?.get(1)
        }.distinct()
        val context = contexts.singleOrNull()
        val body = fragments.filter { it.region != null && it.region.top > headerBottom }
        val anchors = rows(body.filter { column(it) == 0 }).filter { resolve(join(it)) != null }
        val unsupported = fragments.filter { it.region == null && it.raw.isNotBlank() }.toMutableList()
        for (row in rows(body)) {
            val left = row.filter { column(it) == 0 }
            val name = join(left)
            if (name.isNotBlank() && resolve(name) == null && !Regex("^[0-9]+[.)].*").matches(name) &&
                row.any { column(it) == 1 } && !fold(name).startsWith("faixas arbitrarias")) {
                unsupported += row; warnings += ParseWarning.UNSUPPORTED_ANALYTE
            }
        }
        val parsed = anchors.mapIndexed { ordinal, anchor ->
            val id = StableIds.of(runId, page.toString(), anchor.first().spanId, ordinal.toString(), VERSION)
            val ambiguities = mutableSetOf<Ambiguity>()
            if (ParseWarning.UNPOSITIONED_CONTENT in warnings) ambiguities += Ambiguity.REFERENCE_ASSOCIATION
            val associated = body.filter { span -> overlaps(anchor, span) }
            if (associated.any { span -> anchors.count { overlaps(it, span) } != 1 } || associated.any { column(it) == null }) {
                ambiguities += Ambiguity.REFERENCE_ASSOCIATION
                warnings += ParseWarning.COMPETING_ASSOCIATIONS
            }
            val value = associated.filter { column(it) == 1 }
            val unit = associated.filter { column(it) == 2 }
            val referenceCell = associated.filter { column(it) == 3 }
            val resultRaw = join(value)
            val number = numbers.parse(resultRaw)
            val qualitative = resultRaw.isNotBlank() && resultRaw.all { it.isLetter() || it.isWhitespace() }
            if (value.isEmpty() || (number.value == null && !qualitative)) ambiguities += Ambiguity.VALUE
            if (unit.isEmpty()) ambiguities += Ambiguity.UNIT
            if (layout == null) ambiguities += Ambiguity.REFERENCE_ASSOCIATION
            val referenceGroups = if (layout == SupportedLayout.FOOTNOTE) {
                val link = Regex("^ver nota\\s+([0-9]+)$").matchEntire(fold(join(referenceCell)))
                if (link != null) {
                    val targets = rows(body).filter { Regex("^" + link.groupValues[1] + "[.)]\\s*.+").matches(join(it)) }
                    if (targets.size != 1) ambiguities += Ambiguity.REFERENCE_ASSOCIATION
                    targets
                } else {
                    if (referenceCell.isNotEmpty()) ambiguities += Ambiguity.REFERENCE_ASSOCIATION
                    listOf(referenceCell)
                }
            } else listOf(referenceCell)
            val referenceSpans = referenceGroups.flatten()
            val candidates = referenceGroups.flatMap { group ->
                val rawReference = join(group).let {
                    if (layout == SupportedLayout.FOOTNOTE) it.replaceFirst(Regex("^[0-9]+[.)]\\s*"), "") else it
                }
                parseReferences(id, page, rawReference, context)
            }
            val applicable = candidates.filter { it.applicability == Applicability.APPLICABLE }
            val unresolved = candidates.any { it.applicability == Applicability.UNKNOWN }
            if (candidates.size > 1 && (applicable.size != 1 || unresolved)) {
                ambiguities += Ambiguity.REFERENCE_ASSOCIATION
                warnings += ParseWarning.COMPETING_ASSOCIATIONS
            }
            if (unresolved) ambiguities += Ambiguity.CONTEXT
            val selected = when {
                candidates.size == 1 -> candidates.single()
                applicable.size == 1 && !unresolved -> applicable.single()
                else -> null
            }
            val markers = if (layout == SupportedLayout.FOOTNOTE)
                referenceSpans.filter { Regex("^[0-9]+[.)]$").matches(it.raw.trim()) } else emptyList()
            val evidence = listOf("analyteRaw" to anchor, "resultRaw" to value,
                "unitRaw" to unit, "referenceRaw" to (referenceSpans - markers.toSet()),
                "referenceLink" to (if (layout == SupportedLayout.FOOTNOTE) referenceCell else emptyList()),
                "referenceMarker" to markers, "context" to contextRows.flatten()).flatMap { (field, spans) ->
                spans.mapNotNull { span -> span.region?.let { region ->
                    ExtractedFieldEvidence(field, span.raw, region, span.spanId, span.method, span.extractorVersion, span.confidence)
                } }
            }
            val box = anchor.mapNotNull { it.region }.takeIf { it.isNotEmpty() }?.let { regions ->
                BoundingBox(floor(regions.minOf { it.left }).toInt(), floor(regions.minOf { it.top }).toInt(),
                    ceil(regions.maxOf { it.right }).toInt(), ceil(regions.maxOf { it.bottom }).toInt())
            }
            ParsedObservation(Observation(id, documentId, join(anchor), resultRaw, join(unit).ifBlank { null }, page,
                join(associated), box, null, resolve(join(anchor))!!.name, number.value,
                resultComparator = if (qualitative) ResultComparator.EQ else number.comparator,
                ambiguities = ambiguities, fieldEvidence = evidence, referenceCandidates = candidates,
                referenceRaw = referenceGroups.joinToString("\n") { group -> join(group).let {
                    if (layout == SupportedLayout.FOOTNOTE) it.replaceFirst(Regex("^[0-9]+[.)]\\s*"), "") else it
                } }.ifBlank { null }), selected)
        }.toMutableList()
        parsed.groupBy { it.observation.analyteNormalized }.values.filter { it.size > 1 }.flatten().forEach { duplicate ->
            val at = parsed.indexOf(duplicate)
            parsed[at] = duplicate.copy(observation = duplicate.observation.copy(
                ambiguities = duplicate.observation.ambiguities + Ambiguity.REFERENCE_ASSOCIATION))
            warnings += ParseWarning.DUPLICATE_ANALYTE
        }
        return ParsedPage(layout, parsed, warnings, if (layout == null) fragments else unsupported)
    }
    fun parseReferences(id: String, page: Int, raw: String, context: String? = null): List<ReferenceRange> {
        if (raw.isBlank()) return emptyList()
        return raw.split(Regex("\\s*;\\s*|\\s+ou\\s+", RegexOption.IGNORE_CASE)).map { text ->
            val colon = text.indexOf(':')
            val condition = if (colon >= 0) fold(text.substring(0, colon)).trim() else null
            val content = if (colon >= 0) text.substring(colon + 1).trim() else text.trim()
            val applicability = when { condition == null -> Applicability.APPLICABLE
                context == null -> Applicability.UNKNOWN
                context == condition -> Applicability.APPLICABLE
                else -> Applicability.NOT_APPLICABLE }
            val numeric = "([+-]?[0-9]+(?:[.,][0-9]+)*)"
            val unit = "([\\p{L}µμ%][\\p{L}\\p{N}µμ/%^.+\\-]*)"
            val range = Regex("^$numeric\\s*(?:a|até|–|—|-)\\s*$numeric(?:\\s+$unit)?$", RegexOption.IGNORE_CASE).matchEntire(content)
            val unilateral = Regex("^(<=|>=|<|>|≤|≥)\\s*$numeric(?:\\s+$unit)?$").matchEntire(content)
            val halfOpen = Regex("^$numeric\\s*(≤|<=|<)\\s*x\\s*(≤|<=|<)\\s*$numeric(?:\\s+$unit)?$").matchEntire(content)
            var low: java.math.BigDecimal? = null; var high: java.math.BigDecimal? = null
            var comparator = ReferenceComparator.UNKNOWN; var refUnit: String? = null
            var lowInclusive = true; var highInclusive = true
            when {
                range != null -> {
                    low = numbers.parse(range.groupValues[1]).value; high = numbers.parse(range.groupValues[2]).value
                    refUnit = range.groupValues[3].ifBlank { null }
                    if (low != null && high != null) comparator = ReferenceComparator.RANGE
                }
                unilateral != null -> {
                    val value = numbers.parse(unilateral.groupValues[2]).value
                    refUnit = unilateral.groupValues[3].ifBlank { null }
                    if (value != null) when (unilateral.groupValues[1]) {
                        "<", "≤", "<=" -> { high = value; comparator = if (unilateral.groupValues[1] == "<") ReferenceComparator.LT else ReferenceComparator.LTE }
                        else -> { low = value; comparator = if (unilateral.groupValues[1] == ">") ReferenceComparator.GT else ReferenceComparator.GTE }
                    }
                }
                halfOpen != null -> {
                    low = numbers.parse(halfOpen.groupValues[1]).value; high = numbers.parse(halfOpen.groupValues[4]).value
                    lowInclusive = halfOpen.groupValues[2] != "<"; highInclusive = halfOpen.groupValues[3] != "<"
                    refUnit = halfOpen.groupValues[5].ifBlank { null }
                    if (low != null && high != null) comparator = ReferenceComparator.RANGE
                }
            }
            ReferenceRange(id, text, refUnit, page, low, high, comparator, lowInclusive, highInclusive,
                condition, applicability)
        }
    }
    private fun rows(input: List<SourceFragment>): List<List<SourceFragment>> {
        val result = mutableListOf<MutableList<SourceFragment>>()
        for (span in input.sortedWith(compareBy({ it.region!!.top }, { it.region!!.left }))) {
            val matches = result.filter { overlaps(it, span) }
            if (matches.isEmpty()) result += mutableListOf(span)
            else { matches.first() += span; matches.drop(1).forEach { result.remove(it); matches.first().addAll(it) } }
        }
        return result.map { it.sortedBy { span -> span.region!!.left } }
    }
    private fun overlaps(row: List<SourceFragment>, span: SourceFragment): Boolean = row.any {
        val a = it.region!!; val b = span.region!!
        minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) >= minOf(a.bottom - a.top, b.bottom - b.top) * 0.5
    }
    private fun column(span: SourceFragment): Int? {
        val box = span.region ?: return null
        val left = box.left / box.pageWidth; val right = box.right / box.pageWidth
        val edges = listOf(0.0, 0.265, 0.44, 0.59, 1.0)
        return (0..3).singleOrNull { left >= edges[it] && right <= edges[it + 1] }
    }
    private fun join(spans: List<SourceFragment>): String {
        val ordered = spans.sortedBy { it.region?.left ?: Double.MAX_VALUE }
        val out = StringBuilder(); var previous: SourceFragment? = null
        for (span in ordered) {
            val a = previous?.region; val b = span.region
            if (out.isNotEmpty() && a != null && b != null && !out.last().isWhitespace() &&
                !span.raw.firstOrNull().let { it == null || it.isWhitespace() } &&
                (span.spaceBefore ?: (b.left - a.right > maxOf(a.bottom - a.top, b.bottom - b.top) * 0.2))) out.append(' ')
            out.append(span.raw); previous = span
        }
        return out.toString().trim()
    }
    companion object {
        const val VERSION = "ptbr-biochemistry-layouts/1"
        private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
        fun resolve(raw: String): Analyte? = when (fold(raw).removeSuffix(":")) {
            "glicose", "glicemia", "gli" -> Analyte.GLUCOSE
            "ureia", "ure" -> Analyte.UREA
            "creatinina", "crea" -> Analyte.CREATININE
            "sodio", "na", "na+" -> Analyte.SODIUM
            "potassio", "k", "k+" -> Analyte.POTASSIUM
            else -> null
        }
    }
}
