package br.com.exames.app

import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExamObservation
import br.com.exames.domain.validation.SourceRegion
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.text.Normalizer
import kotlin.math.abs

/** Reads the optional private checklist after inference and returns only per-page totals. */
internal object PrivateExamComparison {
    private const val X_MARGIN = 0.025
    private const val Y_MARGIN = 0.007
    private const val MAX_CHECKLIST_BYTES = 256 * 1024

    fun compare(source: InputStream, extraction: ExamExtraction): JSONArray {
        val buffer = ByteArray(MAX_CHECKLIST_BYTES + 1)
        val expected = try {
            var size = 0
            while (size < buffer.size) {
                val read = source.read(buffer, size, buffer.size - size)
                if (read <= 0) break
                size += read
            }
            check(size in 1..MAX_CHECKLIST_BYTES)
            decode(JSONArray(String(buffer, 0, size, Charsets.UTF_8)))
        } catch (_: Exception) {
            // JSON decoder messages may quote input.
            error("PRIVATE_CHECKLIST_INVALID")
        } finally {
            buffer.fill(0)
        }
        return JSONArray(extraction.pages.map { actual ->
            compare(actual.page, actual.observations, expected.getValue(actual.page))
        })
    }

    private fun decode(root: JSONArray): Map<Int, List<Expected>> {
        check(root.length() == 6)
        val result = linkedMapOf<Int, List<Expected>>()
        for (index in 0 until root.length()) {
            val page = root.getJSONObject(index)
            check(page.getInt("page") == index + 1)
            val records = page.getJSONArray("records")
            check(records.length() in 1..80)
            result[index + 1] = (0 until records.length()).map { pos ->
                val row = records.getJSONObject(pos)
                val selections = row.getJSONArray("selections")
                check(selections.length() in 2..80)
                val fields = (0 until selections.length()).map { selection ->
                    val field = selections.getJSONObject(selection)
                    field.getString("kind") to box(field.getJSONArray("rect"))
                }.groupBy({ it.first }, { it.second })
                Expected(box(row.getJSONArray("region")), row.getString("name"), row.getString("result"),
                    row.optionalText("unit"), row.optionalText("reference"), row.getBoolean("referenceReview"), fields)
            }
        }
        return result
    }

    private fun compare(page: Int, actual: List<ExamObservation>, expected: List<Expected>): JSONObject {
        val remaining = expected.toMutableList()
        val totals = linkedMapOf(
            "expected" to expected.size, "exact" to 0, "extras" to 0,
            "measurementDifferences" to 0, "referenceDifferences" to 0,
            "expectedAmbiguities" to expected.count { it.review }, "preservedAmbiguities" to 0,
        )
        fun count(key: String) { totals[key] = totals.getValue(key) + 1 }

        for (row in actual) {
            val region = row.sourceResult.regions.firstOrNull { it.page == page }
            val matches = if (region == null) emptyList() else remaining.filter {
                abs(it.resultBox.left - region.left / region.pageWidth) <= X_MARGIN &&
                    abs(it.resultBox.top - region.top / region.pageHeight) <= Y_MARGIN
            }
            val target = matches.singleOrNull()
            if (target == null) {
                count("extras")
                continue
            }
            remaining.remove(target)
            val measurementsMatch = same(row.name.text, target.name) && same(row.result, target.result) &&
                same(row.unit, target.unit) && touches(page, row.name.regions, target.fields["name"].orEmpty()) &&
                touches(page, row.sourceResult.regions, target.fields["result"].orEmpty()) &&
                touches(page, row.unitCitation?.regions.orEmpty(), target.fields["unit"].orEmpty())
            val referencesMatch = same(row.reference?.text, target.reference) && row.referenceNeedsReview == target.review &&
                touches(page, row.reference?.regions.orEmpty(), target.fields["reference"].orEmpty())
            if (!measurementsMatch) count("measurementDifferences")
            if (!referencesMatch) count("referenceDifferences")
            if (target.review && referencesMatch) count("preservedAmbiguities")
            if (measurementsMatch && referencesMatch) count("exact")
        }
        totals["omissions"] = remaining.size
        return JSONObject(totals).put("page", page)
    }

    private fun box(values: JSONArray): Box {
        check(values.length() == 4)
        val (left, top, right, bottom) = (0..3).map(values::getDouble)
        check(listOf(left, top, right, bottom).all { it in 0.0..1.0 } && right > left && bottom > top)
        return Box(left, top, right, bottom)
    }

    private fun touches(page: Int, actual: List<SourceRegion>, expected: List<Box>): Boolean {
        if (actual.any { it.page != page }) return false
        val boxes = actual.map {
            Box(it.left / it.pageWidth, it.top / it.pageHeight, it.right / it.pageWidth, it.bottom / it.pageHeight)
        }
        return boxes.all { a -> expected.any(a::intersects) } && expected.all { e -> boxes.any(e::intersects) }
    }

    private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else getString(key)

    private fun same(first: String?, second: String?): Boolean =
        first?.let(::canonical) == second?.let(::canonical)

    private fun canonical(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).filterNot(Char::isWhitespace)

    private class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun intersects(other: Box): Boolean =
            left < other.right + X_MARGIN && other.left < right + X_MARGIN &&
                top < other.bottom + Y_MARGIN && other.top < bottom + Y_MARGIN
    }

    private class Expected(val resultBox: Box, val name: String, val result: String, val unit: String?,
        val reference: String?, val review: Boolean, val fields: Map<String, List<Box>>)
}
