package br.com.exames.app

import br.com.exames.domain.extraction.ExamCitation
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExamObservation
import br.com.exames.domain.extraction.ExamPageOutcome
import br.com.exames.domain.validation.SourceRegion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateExamComparisonTest {
    @Test fun repeatedLiteralAtWrongReferencePositionCountsAsDifference() {
        fun region(left: Int, top: Int) = SourceRegion(1, left.toDouble(), top.toDouble(),
            left + 4.0, top + 3.0, 100.0, 100.0)
        fun citation(value: String, left: Int, top: Int) = ExamCitation("opaque", value, listOf(region(left, top)))
        val name = citation("X", 10, 10)
        val value = citation("7", 40, 10)
        val reference = citation("A", 70, 20)
        val wrongReference = citation("A", 70, 40)

        fun area(kind: String, left: Int, top: Int) = JSONObject()
            .put("kind", kind).put("rect", JSONArray(listOf(left / 100.0, top / 100.0, (left + 4) / 100.0, (top + 3) / 100.0)))
        val record = JSONObject().put("name", "X").put("result", "7").put("unit", JSONObject.NULL)
            .put("reference", "A").put("referenceReview", false).put("region", area("result", 40, 10).getJSONArray("rect"))
            .put("selections", JSONArray(listOf(area("name", 10, 10), area("result", 40, 10), area("reference", 70, 20))))
        val checklist = JSONArray((1..6).map { JSONObject().put("page", it).put("records", JSONArray(listOf(record))) })
            .toString().byteInputStream().readBytes()

        fun comparison(actual: List<ExamObservation>) = PrivateExamComparison.compare(checklist.inputStream(),
            ExamExtraction(listOf(ExamPageOutcome(1, actual, emptySet(), emptySet())))).getJSONObject(0)

        assertEquals(1, comparison(listOf(ExamObservation(name, value, "7", null, reference, false))).getInt("exact"))
        val wrong = comparison(listOf(ExamObservation(name, value, "7", null, wrongReference, false)))
        assertEquals(0, wrong.getInt("exact"))
        assertEquals(1, wrong.getInt("referenceDifferences"))
        val misplaced = comparison(listOf(ExamObservation(name, citation("7", 50, 10), "7", null, reference, false)))
        assertEquals(1, misplaced.getInt("omissions"))
        assertEquals(1, misplaced.getInt("extras"))
    }
}
