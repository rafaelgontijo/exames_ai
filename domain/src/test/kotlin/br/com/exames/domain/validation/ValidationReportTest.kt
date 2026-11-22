package br.com.exames.domain.validation

import br.com.exames.domain.model.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ValidationReportTest {
    private val generator = ValidationReportGenerator()
    private fun sample(id: String, expectedCount: Int = 1, correctCount: Int = expectedCount): ReportSample {
        val expected = (0 until expectedCount).map { index -> ExpectedObservation("e$index", Analyte.GLUCOSE,
            "Glicose", "15", "mg/dL", null, "15", null, null, ExpectedStatus.ABOVE_RANGE, "synthetic", true,
            listOf(ExtractedFieldEvidence("analyteRaw", "Glicose", SourceRegion(1, 10.0, 20.0 + index * 30,
                90.0, 30.0 + index * 30, 600.0, 800.0), "s$index", "SYNTHETIC", "test/1"))) }
        val observed = expected.map { Observation("a-${it.observationId}", "doc-$id", "Glicose", "15", "mg/dL", 1,
            "synthetic", numericValue = "15".toBigDecimal(), fieldEvidence = it.evidence) }
        val truth = GroundTruthVersion(caseId = id, version = "1", reviewState = ReviewState.REVIEWED,
            reviewerId = "synthetic", reviewedAt = "2026-10-27T00:00:00Z", observations = expected)
        val case = CorpusCase(id, "family-$id", CorpusOrigin.SYNTHETIC, CorpusFormat.PDF_TEXT,
            CorpusPartition.DEVELOPMENT, "simple-v1", "1", "documents/$id.pdf", "a".repeat(64), 100,
            "truth/$id.json", "b".repeat(64))
        val run = ProcessingRun("run-$id", "doc-$id", case.documentSha256, id, ExtractionMode.BASELINE,
            "extractor/1", "parser/1", "REF_NUMERIC/2.0.0", RunState.COMPLETE, "2026-10-27T00:00:00Z", "S25", "17")
        return ReportSample(case, GroundTruthSnapshot("b".repeat(64), truth), run, observed, emptyMap(),
            observed.mapIndexed { index, observation -> observation.id to Assessment(observation.id,
                if (index < correctCount) AssessmentStatus.ABOVE_RANGE else AssessmentStatus.WITHIN_RANGE,
                ReasonCode.INSIDE_REFERENCE, Instant.EPOCH, run.ruleVersion) }.toMap())
    }
    private fun LocalValidationReport.row(dimension: ReportDimension, value: String) =
        rows.single { it.dimension == dimension && it.value == value }

    @Test fun sumsDenominatorsInsteadOfAveragingPercentagesAndRetainsAbnormalErrors() {
        val report = generator.generate(listOf(sample("one", 1, 1), sample("three", 3, 0)))
        val counts = report.row(ReportDimension.ALL, "ALL").counts
        assertEquals(ReportFraction(1, 4), counts.classificationAccuracy)
        assertEquals(ReportFraction(4, 4), counts.numericCoverage)
        assertEquals(3, counts.abnormalCounts["withinRange"])
        assertEquals(3, counts.abnormalCounts["notSignaled"])
        assertEquals(2, counts.families)
        assertEquals(counts, report.row(ReportDimension.ANALYTE, "GLUCOSE").counts)
        assertNull(report.row(ReportDimension.ANALYTE, "UREA").counts.classificationAccuracy.ratio)
        assertFalse(report.publicCoverageAvailable)
        assertNull(report.row(ReportDimension.ORIGIN, "PUBLIC_DEMONSTRATION").counts.numericCoverage.ratio)
        assertNull(report.row(ReportDimension.FORMAT, "PDF_SCANNED").counts.rawAccuracy.ratio)
    }

    @Test fun excludesPendingTruthFromAccuracyButShowsPendingIncompleteAndLegacyUnavailable() {
        val original = sample("pending", 3, 3)
        val pending = original.copy(truth = original.truth.copy(groundTruth = original.truth.groundTruth.copy(reviewState = ReviewState.PENDING)),
            run = original.run.copy(state = RunState.PARTIAL))
        val report = generator.generate(listOf(sample("reviewed", 1, 0), pending))
        val counts = report.row(ReportDimension.ALL, "ALL").counts
        assertEquals(ReportFraction(0, 1), counts.classificationAccuracy)
        assertEquals(3, counts.pendingExpected); assertEquals(1, counts.pendingSamples); assertEquals(1, counts.incompleteSamples)
        assertEquals(3, counts.isolatedUnavailable["UNREVIEWED_TRUTH"])
        assertEquals(1, counts.isolatedUnavailable["LEGACY_INPUT"])
        assertNull(counts.isolatedStatusAccuracy.ratio)
        assertEquals(4, counts.actualObservations)
        val allPending = generator.generate(listOf(pending)).row(ReportDimension.ALL, "ALL").counts
        assertNull(allPending.classificationAccuracy.ratio)
        assertEquals(0, allPending.reviewedExpected)
    }

    @Test fun versionsModesAndCorrectedOutputsCannotImproveAutomaticMetricsRetroactively() {
        val original = sample("case", 1, 0)
        val corrected = original.copy(basis = OutputBasis.CORRECTED, correctionIds = listOf("correction-id"),
            run = original.run.copy(runId = "corrected-run", parentRunId = original.run.runId,
                outputBasis = OutputBasis.CORRECTED, correctionIds = listOf("correction-id")),
            assessments = original.assessments.mapValues { (_, value) -> value.copy(status = AssessmentStatus.ABOVE_RANGE) })
        val differentVersion = sample("v2").let { it.copy(run = it.run.copy(parserVersion = "parser/2")) }
        val assisted = sample("ai").let { it.copy(run = it.run.copy(mode = ExtractionMode.ASSISTED)) }
        val report = generator.generate(listOf(original, corrected, differentVersion, assisted))
        val totals = report.rows.filter { it.dimension == ReportDimension.ALL }
        assertEquals(4, totals.size)
        assertEquals(ReportFraction(0, 1), totals.single { it.cohort.basis == OutputBasis.AUTOMATIC &&
            it.cohort.parserVersion == "parser/1" && it.cohort.mode == ExtractionMode.BASELINE }.counts.classificationAccuracy)
        assertEquals(ReportFraction(1, 1), totals.single { it.cohort.basis == OutputBasis.CORRECTED }.counts.classificationAccuracy)
        assertEquals(listOf("correction-id"), report.identities.single { it.cohort.basis == OutputBasis.CORRECTED }.correctionIds)
        assertEquals(AssessmentStatus.WITHIN_RANGE, original.assessments.values.single().status)
        assertThrows(IllegalArgumentException::class.java) { generator.generate(listOf(corrected.copy(correctionIds = emptyList()))) }
        assertThrows(IllegalArgumentException::class.java) { generator.generate(listOf(original.copy(correctionIds = listOf("id")))) }
    }

    @Test fun partitionsLayoutsFormatsAndFamiliesStayTraceableWithoutCountingVariantsAsIndependentFamilies() {
        val first = sample("first")
        val second = sample("second").let { it.copy(case = it.case.copy(familyId = first.case.familyId,
            format = CorpusFormat.PNG, layoutId = "footnote-v1", layoutVersion = "2")) }
        val report = generator.generate(listOf(first, second))
        assertEquals(1, report.row(ReportDimension.ALL, "ALL").counts.families)
        assertEquals(1, report.row(ReportDimension.FORMAT, "PNG").counts.samples)
        assertEquals(1, report.row(ReportDimension.LAYOUT, "footnote-v1/2").counts.samples)
        assertNull(report.row(ReportDimension.PARTITION, "HELD_OUT").counts.classificationAccuracy.ratio)
        assertThrows(IllegalArgumentException::class.java) {
            generator.generate(listOf(first, second.copy(case = second.case.copy(partition = CorpusPartition.HELD_OUT))))
        }
        assertThrows(IllegalArgumentException::class.java) { generator.generate(listOf(first, first)) }
        assertThrows(IllegalArgumentException::class.java) { generator.generate(listOf(first.copy(run = first.run.copy(documentSha256 = "wrong")))) }
        assertThrows(IllegalArgumentException::class.java) { generator.generate(listOf(first.copy(run = first.run.copy(state = RunState.RUNNING)))) }
    }

    @Test fun unknownExtrasAreVisibleAndPendingPublicExamplesNeverProduceCoverage() {
        val base = sample("public")
        val unknown = base.observations.single().copy(id = "unknown", analyteRaw = "Desconhecido")
        val pending = base.copy(case = base.case.copy(origin = CorpusOrigin.PUBLIC_DEMONSTRATION,
            provenance = PublicProvenance("https://example.org/synthetic", "2026-10-27", "synthetic-test-only", true, false)),
            truth = base.truth.copy(groundTruth = base.truth.groundTruth.copy(reviewState = ReviewState.PENDING)))
        assertFalse(generator.generate(listOf(pending)).publicCoverageAvailable)
        val report = generator.generate(listOf(base.copy(observations = base.observations + unknown)))
        assertEquals(1, report.row(ReportDimension.ALL, "ALL").counts.extra)
        assertEquals(1, report.row(ReportDimension.ANALYTE, "UNSUPPORTED").counts.extra)
        assertNull(report.row(ReportDimension.ANALYTE, "UNSUPPORTED").counts.classificationAccuracy.ratio)
        assertThrows(IllegalArgumentException::class.java) { generator.generate(listOf(pending.copy(case = pending.case.copy(provenance = null)))) }
    }

    @Test fun reportRoundTripsWithFractionsAndIdentitiesAndDoesNotSerializeRawClinicalFields() {
        val report = generator.generate(listOf(sample("one")))
        val encoded = ValidationJson.format.encodeToString(report)
        assertEquals(report, ValidationJson.format.decodeFromString<LocalValidationReport>(encoded))
        assertFalse(encoded.contains("Glicose")); assertFalse(encoded.contains("mg/dL"))
        assertTrue(encoded.contains("truthSha256")); assertTrue(encoded.contains("deviceModel"))
        assertTrue(generator.generate(emptyList()).identities.isEmpty())
        assertTrue(generator.generate(emptyList()).rows.isEmpty())
        assertFalse(generator.generate(emptyList()).publicCoverageAvailable)
    }

    @Test fun repositoryReportUsesExplicitTruthAndOriginalOutputWithoutApplyingCorrectionsOrWriting() = runBlocking {
        val sample = sample("stored")
        val repository = mockk<ValidationRepository>()
        coEvery { repository.findRun(sample.run.runId) } returns sample.run
        coEvery { repository.groundTruth(sample.run.documentId) } returns listOf(sample.truth,
            sample.truth.copy(sha256 = "c".repeat(64), groundTruth = sample.truth.groundTruth.copy(version = "newer")))
        coEvery { repository.observations(sample.run.runId) } returns sample.observations
        coEvery { repository.reference(any()) } returns null
        coEvery { repository.assessments(sample.run.runId) } returns sample.assessments.values.toList()
        val report = GenerateLocalValidationReport(repository).generate(listOf(ReportSelection(sample.case, sample.run.runId, sample.truth.sha256)))
        assertEquals("1", report.identities.single().truthVersion)
        assertEquals(OutputBasis.AUTOMATIC, report.identities.single().cohort.basis)
        coVerify(exactly = 0) { repository.corrections(any()); repository.appendComparison(any()); repository.commitPage(any()); repository.appendCorrection(any()) }
    }

    @Test fun pendingInputsStillRejectForeignOutputsAndTruthUpdatesStayInSeparateCohorts() {
        val first = sample("first")
        val second = sample("second").let { it.copy(truth = it.truth.copy(groundTruth = it.truth.groundTruth.copy(version = "2"))) }
        assertEquals(2, generator.generate(listOf(first, second)).rows.count { it.dimension == ReportDimension.ALL })
        val pending = first.copy(truth = first.truth.copy(groundTruth = first.truth.groundTruth.copy(reviewState = ReviewState.PENDING)))
        assertThrows(IllegalArgumentException::class.java) {
            generator.generate(listOf(pending.copy(assessments = mapOf("foreign" to pending.assessments.values.single()))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            generator.generate(listOf(first.copy(assessments = first.assessments.mapValues { (_, v) -> v.copy(ruleVersion = "other") })))
        }
        assertThrows(IllegalArgumentException::class.java) { ReportFraction(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { ReportFraction(0, -1) }
    }
}
