package br.com.exames.datalocal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.validation.*
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MeasurementIntegrationTest {
    @Test fun actualPrivatePdfExtractionAndCanonicalEvaluatorAreMeasuredSeparately() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString(); val name = "measurement-$suffix.db"; val alias = "measurement-$suffix"
        val factory = ProtectedDatabaseFactory(context, name, AndroidPayloadKeys(alias))
        val repository = ProtectedValidationRepository(factory); val artifacts = EncryptedArtifactStore(context)
        val documentId = UUID.randomUUID().toString(); val runId = UUID.randomUUID().toString()
        val caseId = StableIds.of("synthetic-private-pdf-measurement")
        // Independently declared before extraction; values are arbitrary synthetic fixtures.
        val labels = listOf("Glicose", "Uréia", "Creatinina", "Sódio", "Potássio")
        val expected = labels.mapIndexed { index, label ->
            val unit = if (index < 3) "mg/dL" else "mmol/L"
            val id = StableIds.of(caseId, index.toString())
            ExpectedObservation(id, Analyte.entries[index], label, "15", unit, "10 a 20 $unit", "15", "10", "20",
                ExpectedStatus.WITHIN_RANGE, "INSIDE_REFERENCE", true,
                listOf(ExtractedFieldEvidence("analyteRaw", label,
                    SourceRegion(1, 25.0, 175.0 + index * 65, 125.0, 194.0 + index * 65, 600.0, 800.0),
                    "expected-$index", "SYNTHETIC", "measurement-fixture/1")),
                CanonicalEvaluationInput("REF_NUMERIC/2.0.0", ResultComparator.EQ, emptySet(), ReferenceSelection.SELECTED,
                    CanonicalReferenceMetadata(id, 1, unit, ReferenceComparator.RANGE, true, true, Applicability.APPLICABLE, null), emptyList()))
        }
        val truth = GroundTruthVersion("1.1", caseId, "2", ReviewState.REVIEWED, "synthetic-author", Instant.EPOCH.toString(), expected)
        try {
            val bytes = SyntheticPdf.biochemistry(false)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            artifacts.write(documentId, "original", bytes.inputStream()); bytes.fill(0)
            repository.save(Document(documentId, "artifact:original", hash, "application/pdf", Instant.EPOCH, 1))
            val run = ProcessingRun(runId, documentId, hash, caseId, ExtractionMode.BASELINE, "PDF_TEXT",
                BiochemistryParser.VERSION, DeterministicRangeEvaluator.RULE_VERSION, RunState.RUNNING,
                Instant.EPOCH.toString(), "synthetic-device", "synthetic-api")
            repository.createRun(run)
            val pages = PrivatePageLoader(repository, artifacts, PdfPreviewRenderer(context, LocalDataModule.pdfSource(artifacts, repository)))
            pages.load(documentId, 1).use { page ->
                val parsed = BiochemistryParser().parse(documentId, runId, 1, page.text.fragments)
                assertEquals(5, parsed.observations.size)
                repository.commitPage(PageBatch(PageCheckpoint(runId, 1, ExtractionMode.BASELINE, PageState.COMPLETE, 0),
                    parsed.observations.map { it.observation }, parsed.observations.mapNotNull { it.referenceForAssessment },
                    parsed.observations.map { DeterministicRangeEvaluator().evaluate(it.observation, it.referenceForAssessment) }))
            }
            repository.updateRunState(runId, RunState.COMPLETE, InferenceOutcome.NOT_ATTEMPTED)
            repository.appendGroundTruth(documentId, GroundTruthSnapshot("a".repeat(64), truth))
            val originals = repository.observations(runId)
            val measured = MeasureValidation(repository).measure(runId, "a".repeat(64))
            assertEquals(MetricFraction(5, 5), measured.endToEnd.classificationAccuracy)
            assertEquals(MetricFraction(20, 20), measured.endToEnd.rawAccuracy)
            assertEquals(MetricFraction(5, 5), measured.isolated.statusAccuracy)
            val changed = truth.copy(version = "3", observations = expected.mapIndexed { index, value ->
                if (index == 0) value.copy(resultRaw = "99", numericValue = "99", expectedStatus = ExpectedStatus.ABOVE_RANGE,
                    expectedReason = "ABOVE_UPPER_BOUND") else value
            })
            repository.appendGroundTruth(documentId, GroundTruthSnapshot("b".repeat(64), changed))
            val second = MeasureValidation(repository).measure(runId, "b".repeat(64))
            assertEquals(MetricFraction(4, 5), second.endToEnd.classificationAccuracy)
            assertEquals(MetricFraction(5, 5), second.isolated.statusAccuracy)
            factory.open().close()
            assertEquals(originals, repository.observations(runId))
            assertEquals(4, repository.comparisons(runId).size)
            val corpusCase = CorpusCase(caseId, caseId, CorpusOrigin.SYNTHETIC, CorpusFormat.PDF_TEXT,
                CorpusPartition.DEVELOPMENT, "simple-v1", "1", "documents/source.pdf", hash, bytes.size.toLong(),
                "truth/source.json", "a".repeat(64))
            val report = GenerateLocalValidationReport(repository).generate(listOf(
                ReportSelection(corpusCase, runId, "a".repeat(64)), ReportSelection(corpusCase, runId, "b".repeat(64))))
            val totals = report.rows.filter { it.dimension == ReportDimension.ALL }.associateBy { it.cohort.truthVersion }
            assertEquals(ReportFraction(5, 5), totals.getValue("2").counts.classificationAccuracy)
            assertEquals(ReportFraction(4, 5), totals.getValue("3").counts.classificationAccuracy)
            assertEquals(ReportFraction(5, 5), totals.getValue("3").counts.isolatedStatusAccuracy)
            assertFalse(report.publicCoverageAvailable)
            assertTrue(report.identities.all { it.cohort.basis == OutputBasis.AUTOMATIC })
            assertEquals(report, ValidationJson.format.decodeFromString<LocalValidationReport>(ValidationJson.format.encodeToString(report)))
        } finally {
            artifacts.deleteDocument(documentId); factory.open().close(); context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
