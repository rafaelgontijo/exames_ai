package br.com.exames.featureresults

import br.com.exames.domain.model.Observation
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.validation.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ComparisonEvidenceTest {
    private val region = SourceRegion(1, 10.0, 10.0, 50.0, 20.0, 600.0, 800.0)
    private val evidence = listOf("analyteRaw", "referenceRaw").map {
        ExtractedFieldEvidence(it, "synthetic", region, it, "SYNTHETIC", "1")
    }
    private val expected = ExpectedObservation("expected", Analyte.GLUCOSE, "Glicose", "15", "u/L", "10 a 20",
        "15", "10", "20", ExpectedStatus.WITHIN_RANGE, "INSIDE_REFERENCE", true, evidence)
    private val truth = GroundTruthSnapshot("a".repeat(64), GroundTruthVersion(caseId = "case", version = "1",
        reviewState = ReviewState.REVIEWED, reviewerId = "synthetic", reviewedAt = Instant.EPOCH.toString(), observations = listOf(expected)))
    private val observation = Observation("observed", "doc", "Glicose", "99", "u/L", 1, "synthetic", fieldEvidence = evidence)
    private val run = ProcessingRun("run", "doc", "b".repeat(64), "case", ExtractionMode.BASELINE, "extractor",
        BiochemistryParser.VERSION, "rule", RunState.COMPLETE, Instant.EPOCH.toString(), "synthetic", "36")
    private val difference = FieldDifference("expected", "raw.referenceRaw", "10 a 20", "30 a 40")
    private val comparison = Comparison("comparison", "run", "case", "1", truth.sha256, Instant.EPOCH.toString(),
        listOf(difference), emptyList(), emptyList(), comparatorVersion = ExtractionComparator.VERSION)

    @Test fun wrongClinicalValueDoesNotPreventEvidenceBasedAssociation() {
        val linked = differenceEvidence(comparison, difference, run, listOf(ResultRow(observation, null)), listOf(truth))
        assertEquals("observed", linked.original!!.observationId)
        assertEquals(listOf(1), linked.original.pages)
        assertEquals("expected", linked.expected!!.observationId)
    }
    @Test fun duplicateRowsNeverSelectOneSilentlyAndOldVersionsDoNotReconstructAnAssociation() {
        val rows = listOf(ResultRow(observation, null), ResultRow(observation.copy(id = "duplicate"), null))
        assertNull(differenceEvidence(comparison, difference, run, rows, listOf(truth)).original)
        val legacy = differenceEvidence(comparison.copy(comparatorVersion = null), difference, run,
            listOf(ResultRow(observation, null)), listOf(truth))
        assertNull(legacy.original); assertNotNull(legacy.expected)
    }
    @Test fun differentTruthHashCannotBorrowRegionsFromAnotherSnapshot() {
        val linked = differenceEvidence(comparison.copy(groundTruthSha256 = "c".repeat(64)), difference, run,
            listOf(ResultRow(observation, null)), listOf(truth))
        assertNull(linked.original); assertNull(linked.expected)
        val foreign = differenceEvidence(comparison.copy(runId = "other-run"), difference, run,
            listOf(ResultRow(observation, null)), listOf(truth))
        assertNull(foreign.original); assertNull(foreign.expected)
    }
}
