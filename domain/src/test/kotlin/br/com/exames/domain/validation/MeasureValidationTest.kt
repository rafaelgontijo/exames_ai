package br.com.exames.domain.validation

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MeasureValidationTest {
    private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    private fun fixture() = ValidationJson.format.decodeFromString<List<GroundTruthVersion>>(
        javaClass.getResourceAsStream("/validation/truth-v2-scenarios.json")!!.bufferedReader().use { it.readText() }).first()

    @Test fun truthOnlyChangesCreateSeparateMeasurementsWithoutReprocessingOrOverwritingOriginals() = runBlocking {
        val truth = fixture(); val first = truth.observations.first(); val input = first.evaluationInput!!
        val run = ProcessingRun("run", "document", "c".repeat(64), truth.caseId, ExtractionMode.BASELINE,
            "synthetic", "synthetic", DeterministicRangeEvaluator.RULE_VERSION, RunState.PARTIAL,
            Instant.EPOCH.toString(), "synthetic", "synthetic")
        val observation = Observation("actual", run.documentId, first.analyteRaw, first.resultRaw, first.unitRaw, 1, "synthetic",
            numericValue = first.numericValue!!.toBigDecimal(), fieldEvidence = first.evidence, referenceRaw = first.referenceRaw)
        val reference = ReferenceRange(observation.id, first.referenceRaw!!, input.selectedReference!!.unit, 1,
            first.low!!.toBigDecimal(), first.high!!.toBigDecimal(), applicability = Applicability.APPLICABLE)
        val assessment = DeterministicRangeEvaluator(clock).evaluate(observation, reference)
        val changed = truth.copy(version = "3", observations = truth.observations.map { it.copy(resultRaw = "999", numericValue = "999", expectedStatus = ExpectedStatus.ABOVE_RANGE) })
        val snapshots = listOf(GroundTruthSnapshot("a".repeat(64), truth), GroundTruthSnapshot("b".repeat(64), changed))
        val stored = mutableListOf<Comparison>(); val repository = mockk<ValidationRepository>()
        coEvery { repository.findRun(run.runId) } returns run
        coEvery { repository.groundTruth(run.documentId) } returns snapshots
        coEvery { repository.observations(run.runId) } returns listOf(observation)
        coEvery { repository.reference(observation.id) } returns reference
        coEvery { repository.assessments(run.runId) } returns listOf(assessment)
        coEvery { repository.comparisons(run.runId) } answers { stored.toList() }
        coEvery { repository.appendComparison(any()) } coAnswers { stored += firstArg<Comparison>() }
        val useCase = MeasureValidation(repository, clock)
        val a = useCase.measure(run.runId, snapshots[0].sha256)
        val b = useCase.measure(run.runId, snapshots[1].sha256)
        assertEquals(run, a.run); assertEquals(run, b.run)
        assertEquals(a.endToEnd.comparison!!.matching, b.endToEnd.comparison!!.matching)
        assertNotEquals(a.endToEnd.rawAccuracy, b.endToEnd.rawAccuracy)
        assertNotEquals(a.isolated.observations.map { it.assessment?.status }, b.isolated.observations.map { it.assessment?.status })
        assertEquals(4, stored.size); assertEquals(setOf("2", "3"), stored.map { it.groundTruthVersion }.toSet())
        assertEquals(2, stored.count { it.kind == MeasurementKind.EVALUATOR_ISOLATED })
        val again = useCase.measure(run.runId, snapshots[0].sha256)
        assertEquals(4, stored.size); assertEquals(a.comparisons, again.comparisons)
        coVerify(exactly = 0) { repository.createRun(any()); repository.commitPage(any()); repository.save(any()); repository.appendGroundTruth(any(), any()) }
    }
    @Test fun activeRunsAndWrongCasesCannotBecomeStableComparisons() = runBlocking {
        val truth = fixture(); val repository = mockk<ValidationRepository>()
        val run = ProcessingRun("run", "document", "c".repeat(64), truth.caseId, ExtractionMode.BASELINE,
            "s", "s", DeterministicRangeEvaluator.RULE_VERSION, RunState.RUNNING, Instant.EPOCH.toString(), "s", "s")
        coEvery { repository.findRun("run") } returns run
        try { MeasureValidation(repository, clock).measure("run", "a".repeat(64)); fail("Active run accepted") }
        catch (_: IllegalArgumentException) { }
        coEvery { repository.findRun("run") } returns run.copy(state = RunState.COMPLETE, caseId = "other-case")
        coEvery { repository.groundTruth("document") } returns listOf(GroundTruthSnapshot("a".repeat(64), truth))
        try { MeasureValidation(repository, clock).measure("run", "a".repeat(64)); fail("Wrong case accepted") }
        catch (_: IllegalArgumentException) { }
        coVerify(exactly = 0) { repository.appendComparison(any()) }
    }
}
