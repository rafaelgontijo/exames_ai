package br.com.exames.domain.validation

import br.com.exames.domain.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExtractionComparatorTest {
    private val comparator = ExtractionComparator()
    private fun evidence(y: Double, width: Double = 600.0, height: Double = 800.0, page: Int = 1) =
        ExtractedFieldEvidence("analyteRaw", "Glicose", SourceRegion(page, width / 24, y, width / 6, y + height / 40, width, height), "source-$y", "SYNTHETIC", "test/1")
    private fun expected(id: String = "expected", y: Double = 175.0) = ExpectedObservation(id, Analyte.GLUCOSE,
        "Glicose", "15", "mg/dL", "10 a 20 mg/dL", "15", "10", "20", ExpectedStatus.WITHIN_RANGE,
        "INSIDE_REFERENCE", true, listOf(evidence(y)))
    private fun actual(id: String = "actual", y: Double = 175.0) = Observation(id, "document", "Glicose", "15", "mg/dL", 1,
        "synthetic", numericValue = "15".toBigDecimal(), fieldEvidence = listOf(evidence(y)), referenceRaw = "10 a 20 mg/dL")

    @Test fun valueChangesCannotChangePairing() {
        val first = comparator.match(listOf(expected()), listOf(actual()))
        val changed = comparator.match(listOf(expected().copy(resultRaw = "999", numericValue = "999")),
            listOf(actual().copy(resultRaw = "-1", numericValue = "-1".toBigDecimal())))
        assertEquals(first, changed); assertEquals(1, first.pairs.size)
    }
    @Test fun absentExtraAndUnsupportedAnalyteRemainExplicit() {
        val output = comparator.match(listOf(expected()), listOf(actual().copy(analyteRaw = "Colesterol")))
        assertEquals(listOf("expected"), output.missing); assertEquals(listOf("actual"), output.extra)
        assertTrue(output.pairs.isEmpty())
    }
    @Test fun duplicateRowsAreResolvedOnlyBySourceEvidence() {
        val expected = listOf(expected("e1", 175.0), expected("e2", 240.0))
        val actual = listOf(actual("a1", 175.0).copy(resultRaw = "999"), actual("a2", 240.0))
        assertEquals(listOf(ObservationPair("e1", "a1"), ObservationPair("e2", "a2")), comparator.match(expected, actual).pairs)
    }
    @Test fun duplicateRowsWithoutGeometryNeverChooseByValueOrInputOrder() {
        val truth = listOf(expected())
        val values = listOf(actual("wrong").copy(fieldEvidence = emptyList(), resultRaw = "999"), actual("right").copy(fieldEvidence = emptyList()))
        val first = comparator.match(truth, values); val reversed = comparator.match(truth, values.reversed())
        assertTrue(first.pairs.isEmpty()); assertEquals(first.unresolved, reversed.unresolved)
        assertEquals(setOf("wrong", "right"), first.extra.toSet()); assertEquals(listOf("expected"), first.missing)
    }
    @Test fun normalizedGeometryAllowsRasterScalingButDoesNotUseProximity() {
        val scaled = actual().copy(fieldEvidence = listOf(evidence(350.0, 1200.0, 1600.0)))
        assertEquals(1, comparator.match(listOf(expected()), listOf(scaled)).pairs.size)
        assertTrue(comparator.match(listOf(expected()), listOf(actual(y = 300.0))).pairs.isEmpty())
    }
    @Test fun unknownExpectedPageCannotBeAssumedAndDifferentPagesNeverPair() {
        assertTrue(comparator.match(listOf(expected().copy(evidence = emptyList())), listOf(actual())).pairs.isEmpty())
        assertTrue(comparator.match(listOf(expected()), listOf(actual().copy(page = 2))).pairs.isEmpty())
    }
    @Test fun rawDifferencesAndCanonicalDecimalEqualityAreSeparate() {
        val observed = actual().copy(resultRaw = "15,0", numericValue = "15.0".toBigDecimal(), unitRaw = " mg/dL ")
        val result = comparator.compare(listOf(expected()), listOf(observed))
        assertFalse(result.raw.single { it.field == "resultRaw" }.matches)
        assertTrue(result.canonical.single { it.field == "numericValue" }.matches)
        assertFalse(result.raw.single { it.field == "unitRaw" }.matches)
        assertTrue(result.canonical.single { it.field == "unit" }.matches)
    }
    @Test fun swappedReferencesAreErrorsWithoutReassigningObservationsToFitRanges() {
        val truth = listOf(expected("e1", 175.0), expected("e2", 240.0).copy(referenceRaw = "30 a 40 mg/dL", low = "30", high = "40"))
        val observed = listOf(actual("a1", 175.0).copy(referenceRaw = "30 a 40 mg/dL"), actual("a2", 240.0))
        val references = mapOf("a1" to ReferenceRange("a1", "30 a 40 mg/dL", "mg/dL", 1, "30".toBigDecimal(), "40".toBigDecimal()),
            "a2" to ReferenceRange("a2", "10 a 20 mg/dL", "mg/dL", 1, "10".toBigDecimal(), "20".toBigDecimal()))
        val output = comparator.compare(truth, observed, references)
        assertEquals(listOf(ObservationPair("e1", "a1"), ObservationPair("e2", "a2")), output.matching.pairs)
        assertTrue(output.raw.filter { it.field == "referenceRaw" }.all { !it.matches })
        assertTrue(output.canonical.filter { it.field == "low" }.all { !it.matches })
    }
    @Test fun rejectsDuplicateIdentitiesAndMixedDocuments() {
        assertThrows(IllegalArgumentException::class.java) { comparator.match(listOf(expected(), expected()), listOf(actual())) }
        assertThrows(IllegalArgumentException::class.java) { comparator.match(listOf(expected()), listOf(actual(), actual())) }
        assertThrows(IllegalArgumentException::class.java) { comparator.match(listOf(expected()), listOf(actual(), actual("other").copy(documentId = "other-doc"))) }
    }
    @Test fun footnotePageCannotReplaceTheAnalytePageForMatching() {
        val truth = expected().copy(evidence = expected().evidence + evidence(175.0, page = 2).copy(field = "referenceRaw"))
        assertTrue(comparator.match(listOf(truth), listOf(actual().copy(page = 2, fieldEvidence = emptyList()))).pairs.isEmpty())
    }
    @Test fun reportsForeignReferenceOwnerAndRejectsInvalidCanonicalGroundTruth() {
        val reference = ReferenceRange("other-observation", "10 a 20 mg/dL", "mg/dL", 1, "10".toBigDecimal(), "20".toBigDecimal())
        val output = comparator.compare(listOf(expected()), listOf(actual()), mapOf("actual" to reference))
        assertFalse(output.canonical.single { it.field == "referenceAssociation" }.matches)
        assertThrows(IllegalArgumentException::class.java) {
            comparator.compare(listOf(expected().copy(numericValue = "1e999999999")), listOf(actual()))
        }
    }
}
