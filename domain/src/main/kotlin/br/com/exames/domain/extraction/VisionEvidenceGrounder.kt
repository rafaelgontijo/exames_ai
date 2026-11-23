package br.com.exames.domain.extraction

import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.SourceRegion
import java.text.Normalizer
import java.util.Locale

data class GroundedVisionPage(
    val observations: List<ExamObservation>,
    val issues: Set<ExamIssue>,
)

/**
 * Pure JVM grounder that cross-checks visual proposals against positioned digital PDF text
 * when available (filtering ungrounded hallucinations and resolving highlight regions), and
 * falls back conservatively to visual-only NEEDS_REVIEW observations on scanned pages.
 */
object VisionEvidenceGrounder {
    private val alphaTok = Regex("[a-z]{3,}")
    private val numTok = Regex("""\d+(?:[.,]\d+)?""")
    private val alphanumeric = Regex("[a-z0-9]")

    private class TextLine(first: SourceFragment) {
        val words = mutableListOf(first)
        var top = first.region!!.top
            private set
        var bottom = first.region!!.bottom
            private set
        var left = first.region!!.left
            private set
        val mid get() = (top + bottom) / 2.0

        fun append(word: SourceFragment) {
            val box = word.region!!
            top = minOf(top, box.top)
            bottom = maxOf(bottom, box.bottom)
            left = minOf(left, box.left)
            words += word
        }

        val sortedWords: List<SourceFragment> by lazy { words.sortedBy { it.region!!.left } }
        val text: String by lazy { joinWords(sortedWords) }
        val folded: String by lazy { fold(text) }
        val compact: String by lazy { folded.replace(" ", "") }
    }

    fun ground(page: Int, proposals: List<VisionProposal>, fragments: List<SourceFragment>): GroundedVisionPage {
        val pageFragments = fragments.filter { it.raw.isNotBlank() && (it.region == null || it.region.page == page) }
        val hasDigitalText = pageFragments.any { alphanumeric.containsMatchIn(fold(it.raw)) }

        if (!hasDigitalText) {
            return groundScannedPage(page, proposals)
        }
        return groundDigitalPage(page, proposals, pageFragments)
    }

    private fun groundScannedPage(page: Int, proposals: List<VisionProposal>): GroundedVisionPage {
        val dedup = VisionExamProtocol.deduplicate(proposals)
        val issues = mutableSetOf(ExamIssue.INSUFFICIENT_POSITIONED_TEXT)
        if (dedup.hasConflicts) issues += ExamIssue.EVIDENCE_NEEDS_REVIEW
        val observations = dedup.items.mapIndexed { idx, proposal ->
            val id = "p${page}vis${idx + 1}"
            ExamObservation(
                name = ExamCitation("${id}n", proposal.name, emptyList()),
                sourceResult = ExamCitation(
                    "${id}v",
                    listOfNotNull(proposal.result, proposal.unit).joinToString(" "),
                    emptyList(),
                ),
                result = proposal.result,
                unit = proposal.unit,
                reference = proposal.reference?.let { ExamCitation("${id}f", it, emptyList()) },
                referenceNeedsReview = true,
                unitCitation = null,
                visualOnly = true,
                page = page,
            )
        }
        return GroundedVisionPage(observations, issues)
    }

    private fun groundDigitalPage(
        page: Int,
        proposals: List<VisionProposal>,
        fragments: List<SourceFragment>,
    ): GroundedVisionPage {
        val positioned = fragments.filter { it.region != null }
        val lines = groupLines(positioned)
        val fullFolded = if (lines.isNotEmpty()) lines.joinToString("\n") { it.folded }
        else fold(fragments.joinToString(" ") { it.raw })
        val fullCompact = fullFolded.replace(" ", "")

        val issues = mutableSetOf<ExamIssue>()
        val candidates = mutableListOf<AnchoredCandidate>()

        for (proposal in proposals) {
            val match = findBestLineMatch(proposal, lines, fullFolded, fullCompact)
            if (match == null) {
                issues += ExamIssue.EVIDENCE_NEEDS_REVIEW
                continue
            }

            val resultRegions = match.resultWords.mapNotNull { it.region }
            val resultLeft = resultRegions.minOfOrNull { it.left }
            val resultRight = resultRegions.maxOfOrNull { it.right }
            val nameRegions = resolveNameRegions(proposal.name, match.lineIndex, lines, resultLeft)
            val unitCitation = resolveUnitCitation(proposal.unit, match.lineIndex, lines, page, candidates.size + 1)
            val refResolution = resolveReference(proposal.reference, match.lineIndex, lines, fullFolded, resultRight)

            val refNeedsReview = proposal.needsReview || refResolution.uncertain
            if (refResolution.uncertain) {
                issues += ExamIssue.EVIDENCE_NEEDS_REVIEW
            }
            candidates += AnchoredCandidate(
                proposal = proposal,
                lineIndex = match.lineIndex,
                anchorTop = resultRegions.firstOrNull()?.top ?: match.line?.top ?: proposal.bandRegion.top,
                nameRegions = nameRegions,
                resultRegions = resultRegions,
                unitCitation = unitCitation,
                referenceText = refResolution.groundedText,
                referenceRegions = refResolution.regions,
                referenceNeedsReview = refNeedsReview,
            )
        }

        val deduplicated = deduplicateAnchored(candidates)
        if (deduplicated.hasConflicts) {
            issues += ExamIssue.EVIDENCE_NEEDS_REVIEW
        }

        val ordered = deduplicated.items.sortedWith(
            compareBy<AnchoredCandidate>({ it.anchorTop }, { it.proposal.bandIndex })
        )
        val observations = ordered.mapIndexed { idx, item ->
            val id = "p${page}g${idx + 1}"
            val rawResultText = listOfNotNull(item.proposal.result, item.proposal.unit).joinToString(" ")
            ExamObservation(
                name = ExamCitation("${id}n", item.proposal.name, item.nameRegions),
                sourceResult = ExamCitation("${id}v", rawResultText, item.resultRegions),
                result = item.proposal.result,
                unit = item.proposal.unit,
                reference = item.referenceText?.let { ExamCitation("${id}f", it, item.referenceRegions) },
                referenceNeedsReview = item.referenceNeedsReview,
                unitCitation = item.unitCitation,
                visualOnly = false,
                page = page,
            )
        }
        return GroundedVisionPage(observations, issues)
    }

    private data class LineMatch(
        val lineIndex: Int,
        val line: TextLine?,
        val resultWords: List<SourceFragment>,
    )

    private data class RefResolution(
        val groundedText: String?,
        val regions: List<SourceRegion>,
        val uncertain: Boolean,
    )

    private data class AnchoredCandidate(
        val proposal: VisionProposal,
        val lineIndex: Int,
        val anchorTop: Double,
        val nameRegions: List<SourceRegion>,
        val resultRegions: List<SourceRegion>,
        val unitCitation: ExamCitation?,
        val referenceText: String?,
        val referenceRegions: List<SourceRegion>,
        val referenceNeedsReview: Boolean,
    )

    private data class DedupAnchored(
        val items: List<AnchoredCandidate>,
        val hasConflicts: Boolean,
    )

    private fun findBestLineMatch(
        proposal: VisionProposal,
        lines: List<TextLine>,
        fullFolded: String,
        fullCompact: String,
    ): LineMatch? {
        val targetFolded = fold(proposal.result)
        val targetCompact = targetFolded.replace(" ", "")
        if (targetCompact.isEmpty()) return null

        if (lines.isEmpty()) {
            return if (containsBoundarySafe(fullFolded, targetFolded) || containsBoundarySafe(fullCompact, targetCompact)) {
                LineMatch(-1, null, emptyList())
            } else {
                null
            }
        }

        val nameTokens = alphaTok.findAll(fold(proposal.name)).map { it.value }.toSet()
        val unitFolded = proposal.unit?.let(::fold)
        val bandTop = proposal.bandRegion.top
        val bandBottom = proposal.bandRegion.bottom
        val bandMargin = (bandBottom - bandTop) * 0.08

        var bestScore = Double.NEGATIVE_INFINITY
        var bestMatch: LineMatch? = null

        for ((idx, line) in lines.withIndex()) {
            val matchedWords = findResultWordsInLine(line, targetFolded, targetCompact) ?: continue
            var score = 1.0
            if (line.bottom >= bandTop - bandMargin && line.top <= bandBottom + bandMargin) {
                score += 3.0
            }
            if (nameTokens.isNotEmpty()) {
                val contextText = buildString {
                    if (idx > 0) append(lines[idx - 1].folded).append(' ')
                    append(line.folded)
                }
                val hits = nameTokens.count { it in contextText }
                score += (hits.toDouble() / nameTokens.size) * 4.0
            }
            if (unitFolded != null && unitFolded in line.folded) {
                score += 1.5
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = LineMatch(idx, line, matchedWords)
            }
        }
        return bestMatch
    }

    private fun findResultWordsInLine(
        line: TextLine,
        targetFolded: String,
        targetCompact: String,
    ): List<SourceFragment>? {
        val words = line.sortedWords
        // Try contiguous word spans from shortest (1 word) up to 4 words.
        val maxSpan = minOf(4, words.size)
        for (span in 1..maxSpan) {
            for (start in 0..(words.size - span)) {
                val sub = words.subList(start, start + span)
                val joinedFolded = fold(joinWords(sub))
                val joinedCompact = joinedFolded.replace(" ", "")
                if (joinedFolded == targetFolded || joinedCompact == targetCompact) {
                    return sub
                }
                if (span == 1 && containsBoundarySafe(joinedFolded, targetFolded)) {
                    return sub
                }
            }
        }
        // Fallback: line contains boundary-safe match (e.g., comparator attached or punctuation suffix)
        if (containsBoundarySafe(line.folded, targetFolded) || containsBoundarySafe(line.compact, targetCompact)) {
            val tokenMatched = words.filter { word ->
                val wf = fold(word.raw)
                containsBoundarySafe(wf, targetFolded) || targetCompact.contains(wf.replace(" ", "")) && wf.any(Char::isDigit)
            }
            return tokenMatched.ifEmpty { words }
        }
        return null
    }

    private fun resolveNameRegions(
        name: String,
        lineIndex: Int,
        lines: List<TextLine>,
        resultLeft: Double? = null,
    ): List<SourceRegion> {
        if (lineIndex < 0 || lineIndex >= lines.size) return emptyList()
        val tokens = alphaTok.findAll(fold(name)).map { it.value }.toSet()
        val matched = mutableListOf<SourceRegion>()
        val searchIndices = buildList {
            if (lineIndex > 0) add(lineIndex - 1)
            add(lineIndex)
        }
        for (idx in searchIndices) {
            val line = lines[idx]
            val lineHits = line.sortedWords.filter { w ->
                val wf = fold(w.raw)
                val positionOk = idx != lineIndex || resultLeft == null || (w.region?.right ?: 0.0) <= resultLeft + 8.0
                positionOk && tokens.any { t -> wf.contains(t) || (wf.length >= 3 && t.contains(wf)) }
            }
            if (lineHits.isNotEmpty() && idx == lineIndex) {
                matched.clear()
                matched.addAll(lineHits.mapNotNull { it.region })
                break
            } else if (lineHits.isNotEmpty()) {
                matched.addAll(lineHits.mapNotNull { it.region })
            }
        }
        return matched
    }

    private fun resolveUnitCitation(
        unit: String?,
        lineIndex: Int,
        lines: List<TextLine>,
        page: Int,
        seq: Int,
    ): ExamCitation? {
        if (unit == null || lineIndex < 0 || lineIndex >= lines.size) return null
        val target = fold(unit).replace(" ", "")
        val hits = lines[lineIndex].sortedWords.filter { fold(it.raw).replace(" ", "") == target }
        if (hits.isEmpty()) return null
        return ExamCitation("p${page}u$seq", unit, hits.mapNotNull { it.region })
    }

    private fun resolveReference(
        reference: String?,
        lineIndex: Int,
        lines: List<TextLine>,
        fullFolded: String,
        resultRight: Double? = null,
    ): RefResolution {
        if (reference == null) return RefResolution(null, emptyList(), uncertain = false)
        val fRef = fold(reference)
        val refNums = numTok.findAll(fRef).map { it.value }.toList()

        val grounded = when {
            fRef in fullFolded -> true
            refNums.isNotEmpty() -> refNums.all { n ->
                containsBoundarySafe(fullFolded, n) || containsBoundarySafe(fullFolded, n.replace('.', ','))
            }
            else -> {
                val words = alphaTok.findAll(fRef).map { it.value }.toList()
                words.isNotEmpty() && words.count { it in fullFolded }.toDouble() / words.size >= 0.6
            }
        }
        if (!grounded) {
            return RefResolution(groundedText = null, regions = emptyList(), uncertain = true)
        }

        if (lineIndex < 0 || lines.isEmpty()) {
            return RefResolution(reference, emptyList(), uncertain = false)
        }

        // Look on the matched line (to the right of the result) and up to 4 lines below for reference regions.
        val endIndex = minOf(lines.lastIndex, lineIndex + 4)
        val regions = mutableListOf<SourceRegion>()
        val refWords = alphaTok.findAll(fRef).map { it.value }.toSet()
        for (i in lineIndex..endIndex) {
            val line = lines[i]
            val hits = line.sortedWords.filter { w ->
                val positionOk = i != lineIndex || resultRight == null || (w.region?.left ?: Double.MAX_VALUE) >= resultRight - 4.0
                if (!positionOk) return@filter false
                val wf = fold(w.raw)
                (refNums.isNotEmpty() && refNums.any { n -> containsBoundarySafe(wf, n) || containsBoundarySafe(wf, n.replace('.', ',')) }) ||
                    (refNums.isEmpty() && refWords.any { rw -> wf.contains(rw) })
            }
            if (hits.isNotEmpty()) {
                regions.addAll(hits.mapNotNull { it.region })
                if (i == lineIndex && refNums.all { n -> containsBoundarySafe(line.folded, n) }) break
            }
        }
        return RefResolution(reference, regions, uncertain = false)
    }

    private fun deduplicateAnchored(candidates: List<AnchoredCandidate>): DedupAnchored {
        val merged = mutableListOf<AnchoredCandidate>()
        var hasConflicts = false

        for (candidate in candidates) {
            val sameIdx = merged.indexOfFirst { existing ->
                fold(existing.proposal.name) == fold(candidate.proposal.name) &&
                    VisionExamProtocol.normResult(existing.proposal.result) == VisionExamProtocol.normResult(candidate.proposal.result) &&
                    fold(existing.proposal.unit.orEmpty()) == fold(candidate.proposal.unit.orEmpty()) &&
                    (existing.lineIndex == candidate.lineIndex || kotlin.math.abs(existing.lineIndex - candidate.lineIndex) <= 1)
            }
            if (sameIdx >= 0) {
                val existing = merged[sameIdx]
                merged[sameIdx] = when {
                    existing.referenceText == null && candidate.referenceText != null ->
                        candidate.copy(referenceNeedsReview = existing.referenceNeedsReview && candidate.referenceNeedsReview)
                    existing.referenceText != null && candidate.referenceText == null -> existing
                    existing.resultRegions.isEmpty() && candidate.resultRegions.isNotEmpty() -> candidate
                    else -> existing.copy(
                        referenceNeedsReview = existing.referenceNeedsReview || candidate.referenceNeedsReview
                    )
                }
                continue
            }

            val conflictIdx = merged.indexOfFirst { existing ->
                existing.lineIndex == candidate.lineIndex &&
                    existing.lineIndex >= 0 &&
                    fold(existing.proposal.name) == fold(candidate.proposal.name) &&
                    fold(existing.proposal.unit.orEmpty()) == fold(candidate.proposal.unit.orEmpty()) &&
                    VisionExamProtocol.normResult(existing.proposal.result) != VisionExamProtocol.normResult(candidate.proposal.result)
            }
            if (conflictIdx >= 0) {
                hasConflicts = true
                merged[conflictIdx] = merged[conflictIdx].copy(referenceNeedsReview = true)
                merged += candidate.copy(referenceNeedsReview = true)
            } else {
                merged += candidate
            }
        }
        return DedupAnchored(merged, hasConflicts)
    }

    private fun groupLines(words: List<SourceFragment>): List<TextLine> {
        val lines = mutableListOf<TextLine>()
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
                lines += TextLine(word)
            }
        }
        return lines
    }

    private fun joinWords(words: List<SourceFragment>): String = buildString {
        var previous: SourceFragment? = null
        for (word in words) {
            val before = previous
            if (before != null) {
                val gap = if (word.region != null && before.region != null) {
                    word.region.left - before.region.right > (word.region.bottom - word.region.top) * 0.2
                } else {
                    true
                }
                if (word.spaceBefore != false || gap) append(' ')
            }
            append(word.raw.trim())
            previous = word
        }
    }

    internal fun containsBoundarySafe(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        var start = haystack.indexOf(needle)
        while (start >= 0) {
            val end = start + needle.length
            val leftOk = start == 0 || !isAdjacentTokenChar(haystack[start - 1], needle.first())
            val rightOk = end == haystack.length || !isAdjacentTokenChar(haystack[end], needle.last())
            if (leftOk && rightOk) return true
            start = haystack.indexOf(needle, start + 1)
        }
        return false
    }

    private fun isAdjacentTokenChar(neighbor: Char, edge: Char): Boolean {
        if (edge.isDigit()) return neighbor.isDigit() || neighbor == ',' || neighbor == '.'
        if (edge.isLetter()) return neighbor.isLetterOrDigit()
        return false
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
}
