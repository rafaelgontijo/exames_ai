package br.com.exames.datalocal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReviewWorkflowTest {
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "review-${UUID.randomUUID()}.db"
        val alias = "review-${UUID.randomUUID()}"
        val factory = ProtectedDatabaseFactory(context, name, AndroidPayloadKeys(alias))
        val repo = ProtectedValidationRepository(factory)
        val document = Document(UUID.randomUUID().toString(), "artifact:original", "c".repeat(64), "application/pdf", Instant.EPOCH, 1)
        val run = ProcessingRun(UUID.randomUUID().toString(), document.id, document.hash, "synthetic-case", ExtractionMode.BASELINE,
            "synthetic/1", "synthetic/1", DeterministicRangeEvaluator.RULE_VERSION, RunState.RUNNING,
            Instant.EPOCH.toString(), "synthetic-device", "synthetic-api")
        val observation = Observation(UUID.randomUUID().toString(), document.id, "Glicose", "15", "mg/dL", 1,
            "synthetic-review-canary", numericValue = "15".toBigDecimal(), referenceRaw = "10 a 20 mg/dL")
        val reference = ReferenceRange(observation.id, observation.referenceRaw!!, "mg/dL", 1,
            "10".toBigDecimal(), "20".toBigDecimal(), applicability = Applicability.APPLICABLE)
        val assessment = DeterministicRangeEvaluator().evaluate(observation, reference)
        suspend fun prepare() {
            repo.save(document); repo.createRun(run)
            repo.commitPage(PageBatch(PageCheckpoint(run.runId, 1, run.mode, PageState.COMPLETE, 123),
                listOf(observation), listOf(reference), listOf(assessment)))
            repo.updateRunState(run.runId, RunState.COMPLETE, InferenceOutcome.NOT_ATTEMPTED)
        }
        suspend fun close() {
            factory.open().close(); context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
    @Test fun correctionsReevaluationAndTruthVersionsPreserveOriginalMeasurementsAcrossReopen() = runBlocking {
        val f = Fixture()
        try { withTimeout(30000) {
            f.prepare()
            val expected = ExpectedObservation("expected", Analyte.GLUCOSE, "Glicose", "15", "mg/dL", "10 a 20 mg/dL",
                "15", "10", "20", ExpectedStatus.WITHIN_RANGE, "INSIDE_REFERENCE", true, emptyList())
            val truth = GroundTruthVersion("1.0", "synthetic-case", "1", ReviewState.REVIEWED, "synthetic-author", Instant.EPOCH.toString(), listOf(expected))
            f.repo.appendGroundTruth(f.document.id, GroundTruthSnapshot("a".repeat(64), truth))
            MeasureValidation(f.repo).measure(f.run.runId, "a".repeat(64))
            val beforeRun = f.repo.findRun(f.run.runId)
            val beforeComparisons = f.repo.comparisons(f.run.runId)
            val correctionId = UUID.randomUUID().toString()
            SaveExtractionCorrection(f.repo).save(f.run.runId, f.observation.id, CorrectionField.RESULT, "15", "25", correctionId)
            assertEquals(listOf(f.observation), f.repo.observations(f.run.runId))
            assertEquals(listOf(f.assessment), f.repo.assessments(f.run.runId))
            val childId = UUID.randomUUID().toString()
            val child = ReevaluateRun(f.repo, ProcessingEnvironment("synthetic-device", "synthetic-api")).reevaluate(f.run.runId, childId)
            assertEquals(OutputBasis.CORRECTED, child.outputBasis)
            assertEquals(listOf(correctionId), child.correctionIds)
            assertEquals(AssessmentStatus.ABOVE_RANGE, f.repo.assessments(childId).single().status)
            assertEquals("25", f.repo.observations(childId).single().resultRaw)
            val changedTruth = truth.copy(version = "2", observations = listOf(expected.copy(resultRaw = "25", numericValue = "25",
                expectedStatus = ExpectedStatus.ABOVE_RANGE, expectedReason = "ABOVE_UPPER_BOUND")))
            val hash = MessageDigest.getInstance("SHA-256").digest(ValidationJson.format.encodeToString(changedTruth).toByteArray())
                .joinToString("") { "%02x".format(it) }
            val revision = GroundTruthSnapshot(hash, changedTruth, "a".repeat(64))
            f.repo.appendGroundTruth(f.document.id, revision)
            MeasureValidation(f.repo).measure(childId, hash)
            assertEquals(beforeComparisons, f.repo.comparisons(f.run.runId))
            assertEquals(beforeRun, f.repo.findRun(f.run.runId))
            val corpusCase = CorpusCase("synthetic-case", "synthetic-family", CorpusOrigin.SYNTHETIC, CorpusFormat.PDF_TEXT,
                CorpusPartition.DEVELOPMENT, "simple", "1", "documents/source.pdf", f.document.hash, 1,
                "truth/source.json", "a".repeat(64))
            val report = GenerateLocalValidationReport(f.repo).generate(listOf(ReportSelection(corpusCase, f.run.runId, "a".repeat(64)),
                ReportSelection(corpusCase, childId, hash)))
            assertEquals(setOf(OutputBasis.AUTOMATIC, OutputBasis.CORRECTED), report.identities.map { it.cohort.basis }.toSet())
            assertEquals(setOf("1", "2"), report.identities.map { it.cohort.truthVersion }.toSet())
            f.factory.open().close()
            assertEquals(child, f.repo.findRun(childId))
            assertEquals(revision, f.repo.groundTruth(f.document.id).single { it.sha256 == hash })
            assertEquals(beforeComparisons, f.repo.comparisons(f.run.runId))
            assertEquals("25", f.repo.corrections(f.observation.id).single().newValue)
            assertEquals(child, ReevaluateRun(f.repo, ProcessingEnvironment("s", "s")).reevaluate(f.run.runId, childId))
            f.repo.deleteCompletely(f.document.id)
            assertNull(f.repo.findRun(childId)); assertNull(f.repo.findRun(f.run.runId))
            assertTrue(f.repo.groundTruth(f.document.id).isEmpty())
            assertTrue(f.repo.corrections(f.observation.id).isEmpty())
            assertTrue(f.repo.comparisons(childId).isEmpty())
        } } finally { f.close() }
    }
    @Test fun failedSecondPageRollsBackTheEntireReevaluationTransaction() = runBlocking {
        val f = Fixture()
        try { withTimeout(30000) {
            f.prepare(); f.repo.save(f.document.copy(pageCount = 2))
            val child = f.run.copy(runId = UUID.randomUUID().toString(), parentRunId = f.run.runId, state = RunState.PARTIAL,
                outputBasis = OutputBasis.REASSESSED, projectionVersion = CorrectedObservationProjection.VERSION)
            val projected = CorrectedObservationProjection().project(f.observation, f.reference, f.assessment, emptyList(), UUID.randomUUID().toString())
            val first = PageBatch(PageCheckpoint(child.runId, 1, child.mode, PageState.COMPLETE, null,
                inferenceOutcome = InferenceOutcome.NOT_ATTEMPTED), listOf(projected.first), listOf(projected.second!!),
                listOf(DeterministicRangeEvaluator().evaluate(projected.first, projected.second)))
            // Invalid page output reaches commitPage only after the first page was written.
            val second = first.copy(checkpoint = first.checkpoint.copy(page = 2))
            try { f.repo.commitReevaluation(child, listOf(first, second)); fail("Invalid batch accepted") }
            catch (_: IllegalArgumentException) { }
            assertNull(f.repo.findRun(child.runId))
            assertTrue(f.repo.observations(child.runId).isEmpty())
            assertTrue(f.repo.checkpoints(child.runId).isEmpty())
            assertEquals(listOf(f.observation), f.repo.observations(f.run.runId))
        } } finally { f.close() }
    }
    @Test fun staleCorrectionSnapshotCannotCommitChildAndStaleEditCannotReplaceAudit() = runBlocking {
        val f = Fixture()
        try { withTimeout(30000) {
            f.prepare()
            val stale = f.run.copy(runId = UUID.randomUUID().toString(), parentRunId = f.run.runId, state = RunState.COMPLETE,
                outputBasis = OutputBasis.REASSESSED, projectionVersion = CorrectedObservationProjection.VERSION)
            SaveExtractionCorrection(f.repo).save(f.run.runId, f.observation.id, CorrectionField.RESULT, "15", "25", UUID.randomUUID().toString())
            try { SaveExtractionCorrection(f.repo).save(f.run.runId, f.observation.id, CorrectionField.RESULT, "15", "30", UUID.randomUUID().toString()); fail("Stale edit accepted") }
            catch (_: IllegalArgumentException) { }
            try { f.repo.commitReevaluation(stale, emptyList()); fail("Stale snapshot accepted") }
            catch (_: IllegalArgumentException) { }
            assertNull(f.repo.findRun(stale.runId))
            assertEquals(1, f.repo.corrections(f.observation.id).size)
            assertEquals(listOf(f.observation), f.repo.observations(f.run.runId))
        } } finally { f.close() }
    }
    @Test fun truthRevisionRejectsWrongParentCaseOrHashWithoutReplacingOriginal() = runBlocking {
        val f = Fixture()
        try {
            f.prepare()
            val truth = GroundTruthVersion("1.0", "synthetic-case", "1", ReviewState.PENDING, null, null, emptyList())
            f.repo.appendGroundTruth(f.document.id, GroundTruthSnapshot("a".repeat(64), truth))
            for (candidate in listOf(GroundTruthSnapshot("b".repeat(64), truth.copy(version = "2"), "a".repeat(64)),
                GroundTruthSnapshot("b".repeat(64), truth.copy(caseId = "other", version = "2"), "a".repeat(64)))) {
                try { f.repo.appendGroundTruth(f.document.id, candidate); fail("Invalid revision accepted") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(listOf(GroundTruthSnapshot("a".repeat(64), truth)), f.repo.groundTruth(f.document.id))
        } finally { f.close() }
    }
}
