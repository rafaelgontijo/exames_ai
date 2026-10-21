package br.com.exames.datalocal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProtectedRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-10-21T00:00:00Z")
    private val evaluator = DeterministicRangeEvaluator(Clock.fixed(now, ZoneOffset.UTC))
    private val document = Document("doc", "SYNTHETIC-PRIVATE-SOURCE", "a".repeat(64), "application/pdf", now, 1)
    private fun run(id: String, doc: Document = document, parent: String? = null) = ProcessingRun(id, doc.id, doc.hash,
        "case", ExtractionMode.BASELINE, "ocr-1", "parser-1", DeterministicRangeEvaluator.RULE_VERSION,
        RunState.RUNNING, now.toString(), "synthetic-device", "24", parentRunId = parent)
    private fun batch(run: ProcessingRun, id: String = "obs", raw: String = "15"): PageBatch {
        val observation = Observation(id, run.documentId, "SYNTHETIC-CLINICAL-MARKER", raw, "u/L", 1,
            "SYNTHETIC-FIELD-SOURCE", numericValue = raw.toBigDecimal(), confirmed = false)
        val reference = ReferenceRange(id, "10–20 u/L", "u/L", 1, "10".toBigDecimal(), "20".toBigDecimal(),
            applicability = Applicability.APPLICABLE)
        return PageBatch(PageCheckpoint(run.runId, 1, run.mode, PageState.COMPLETE, 12),
            listOf(observation), listOf(reference), listOf(evaluator.evaluate(observation, reference)))
    }
    private fun truth(version: String) = GroundTruthVersion(caseId = "case", version = version,
        reviewState = ReviewState.REVIEWED, reviewerId = "synthetic-reviewer", reviewedAt = now.toString(),
        observations = listOf(ExpectedObservation("expected", Analyte.GLUCOSE, "Glicose", "15", "u/L", "10–20 u/L",
            "15", "10", "20", ExpectedStatus.WITHIN_RANGE, "INSIDE_REFERENCE", true, emptyList())))

    private fun withRepository(body: suspend (ProtectedDatabaseFactory, ProtectedValidationRepository) -> Unit) = runBlocking {
        val name = "synthetic-${UUID.randomUUID()}.db"
        val alias = "synthetic-${UUID.randomUUID()}"
        val factory = ProtectedDatabaseFactory(context, name, AndroidPayloadKeys(alias))
        try { body(factory, ProtectedValidationRepository(factory)) }
        finally {
            factory.open().close()
            for (suffix in listOf("", ".legacy-backup", ".protected-staging")) context.deleteDatabase(name + suffix)
            File(context.noBackupFilesDir, "$name.protected-migration").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun reopeningKeepsOriginalsCorrectionsGroundTruthAndComparisonsSeparate() = withRepository { factory, repository ->
        repository.save(document)
        repository.createRun(run("original"))
        val original = batch(run("original"))
        assertTrue(repository.commitPage(original))
        assertFalse(repository.commitPage(original))
        repository.appendCorrection(ManualCorrection("c1", "obs", CorrectionField.RESULT, "15", "16", now))
        repository.appendCorrection(ManualCorrection("c2", "obs", CorrectionField.RESULT, "16", null, now))
        repository.appendCorrection(ManualCorrection("c3", "obs", CorrectionField.RESULT, null, "17", now))
        try {
            repository.appendCorrection(ManualCorrection("stale", "obs", CorrectionField.RESULT, "15", "18", now))
            fail("Stale correction was accepted")
        } catch (_: IllegalArgumentException) { }
        repository.createRun(run("corrected", parent = "original"))
        repository.commitPage(batch(run("corrected"), "obs-corrected", "16"))
        repository.updateRunState("original", RunState.COMPLETE, InferenceOutcome.NOT_ATTEMPTED)
        repository.updateRunState("corrected", RunState.COMPLETE, InferenceOutcome.NOT_ATTEMPTED)
        repository.appendGroundTruth("doc", GroundTruthSnapshot("b".repeat(64), truth("1")))
        repository.appendComparison(Comparison("comparison-1", "original", "case", "1", "b".repeat(64),
            now.toString(), emptyList(), emptyList(), emptyList()))
        repository.appendGroundTruth("doc", GroundTruthSnapshot("c".repeat(64), truth("2")))
        repository.appendComparison(Comparison("comparison-2", "original", "case", "2", "c".repeat(64),
            now.toString(), emptyList(), emptyList(), emptyList()))
        factory.open().close()
        assertEquals(document, repository.observeDocuments().first().single())
        assertEquals(2, repository.observeRuns("doc").first().size)
        assertEquals("15", repository.observations("original").single().resultRaw)
        assertFalse(repository.observations("original").single().confirmed)
        assertEquals("16", repository.observations("corrected").single().resultRaw)
        assertEquals(AssessmentStatus.WITHIN_RANGE, repository.assessments("original").single().status)
        assertEquals(original.references.single(), repository.reference("obs"))
        assertEquals(listOf("16", null, "17"), repository.corrections("obs").map { it.newValue })
        assertEquals(listOf("1", "2"), repository.groundTruth("doc").map { it.groundTruth.version })
        assertEquals(setOf("1", "2"), repository.comparisons("original").map { it.groundTruthVersion }.toSet())
        assertEquals(original.checkpoint, repository.checkpoints("original").single())
    }

    @Test fun pageWritesAreAtomicAndDeletionRejectsLateWriters() = withRepository { _, repository ->
        val other = document.copy(id = "other-doc")
        repository.save(document)
        repository.save(other)
        repository.createRun(run("run-a"))
        repository.createRun(run("run-b", other))
        repository.commitPage(batch(run("run-b", other), "shared-id"))
        val good = batch(run("run-a"), "new-id")
        val conflicting = batch(run("run-a"), "shared-id")
        try {
            repository.commitPage(PageBatch(good.checkpoint, good.observations + conflicting.observations,
                good.references + conflicting.references, good.assessments + conflicting.assessments))
            fail("Duplicate output identity was accepted")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertTrue(repository.observations("run-a").isEmpty())
        assertTrue(repository.checkpoints("run-a").isEmpty())
        assertEquals(1, repository.observations("run-b").size)
        assertTrue(repository.commitPage(good))
        repository.deleteCompletely("doc")
        assertNull(repository.find("doc"))
        assertNull(repository.findRun("run-a"))
        assertTrue(repository.observations("run-a").isEmpty())
        try { repository.commitPage(good); fail("Deleted document was resurrected") }
        catch (_: IllegalStateException) { }
        try { repository.save(document); fail("Pending deletion was ignored") }
        catch (_: IllegalStateException) { }
        assertNotNull(repository.find("other-doc"))
    }

    @Test fun incompleteRunsCannotBeReportedCompleteOrReopenedByStateUpdates() = withRepository { _, repository ->
        repository.save(document)
        repository.createRun(run("run").copy(state = RunState.QUEUED))
        try { repository.updateRunState("run", RunState.COMPLETE, InferenceOutcome.NOT_ATTEMPTED); fail() }
        catch (_: IllegalArgumentException) { }
        repository.updateRunState("run", RunState.RUNNING, InferenceOutcome.NOT_ATTEMPTED)
        try { repository.updateRunState("run", RunState.COMPLETE, InferenceOutcome.NOT_ATTEMPTED); fail() }
        catch (_: IllegalArgumentException) { }
        repository.commitPage(batch(run("run")))
        try { repository.updateRunState("run", RunState.COMPLETE, InferenceOutcome.SUCCEEDED); fail() }
        catch (_: IllegalArgumentException) { }
        repository.updateRunState("run", RunState.COMPLETE, InferenceOutcome.NOT_ATTEMPTED)
        try { repository.updateRunState("run", RunState.RUNNING, InferenceOutcome.NOT_ATTEMPTED); fail() }
        catch (_: IllegalArgumentException) { }
        try { repository.save(document.copy(uriLocal = "changed")); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun persistedFilesAndJournalsDoNotContainSensitiveMarkers() = withRepository { factory, repository ->
        repository.save(document)
        repository.createRun(run("privacy-run"))
        repository.commitPage(batch(run("privacy-run")))
        repository.appendCorrection(ManualCorrection("privacy-correction", "obs", CorrectionField.RESULT,
            "15", "SYNTHETIC-CORRECTION-MARKER", now))
        repository.appendGroundTruth("doc", GroundTruthSnapshot("d".repeat(64), truth("1")))
        repository.appendComparison(Comparison("privacy-comparison", "privacy-run", "case", "1", "d".repeat(64),
            now.toString(), listOf(FieldDifference("expected", "resultRaw", "SYNTHETIC-EXPECTED-MARKER", "15")),
            emptyList(), emptyList()))
        val database = factory.open()
        val path = database.openHelper.writableDatabase.path
        database.close()
        val files = listOf(File(path).parentFile!!, context.cacheDir, context.filesDir, context.noBackupFilesDir)
            .flatMap { root -> if (root.exists()) root.walkTopDown().filter { it.isFile }.toList() else emptyList() }
            .distinctBy { it.absolutePath }
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val content = file.readBytes().toString(Charsets.UTF_8)
            for (marker in listOf("SYNTHETIC-PRIVATE-SOURCE", "SYNTHETIC-CLINICAL-MARKER", "SYNTHETIC-FIELD-SOURCE",
                "SYNTHETIC-CORRECTION-MARKER", "SYNTHETIC-EXPECTED-MARKER", "INSIDE_REFERENCE", "WITHIN_RANGE")) {
                assertFalse("Sensitive synthetic marker persisted in plaintext", content.contains(marker))
            }
        }
    }

    @Test fun caseAndPageOwnershipAreCheckedBeforePersisting() = withRepository { _, repository ->
        repository.save(document)
        repository.createRun(run("run"))
        val first = batch(run("run"))
        try {
            repository.commitPage(first.copy(checkpoint = first.checkpoint.copy(page = 2),
                observations = first.observations.map { it.copy(page = 2) }))
            fail("Page outside document accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(repository.checkpoints("run").isEmpty())
        repository.appendGroundTruth("doc", GroundTruthSnapshot("b".repeat(64), truth("1").copy(caseId = "another-case")))
        try {
            repository.appendComparison(Comparison("wrong-case", "run", "another-case", "1", "b".repeat(64),
                now.toString(), emptyList(), emptyList(), emptyList()))
            fail("Comparison belongs to another corpus case")
        } catch (_: IllegalArgumentException) { }
        assertTrue(repository.comparisons("run").isEmpty())
    }
}
