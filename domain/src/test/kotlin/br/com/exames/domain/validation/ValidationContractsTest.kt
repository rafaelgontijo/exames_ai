package br.com.exames.domain.validation

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ValidationContractsTest {
    private val json = ValidationJson.format

    @Test fun stableIdsPreserveBoundariesAndUnicode() {
        assertEquals(StableIds.of("sódio", "1"), StableIds.of("sódio", "1"))
        assertNotEquals(StableIds.of("a", "bc"), StableIds.of("ab", "c"))
        assertNotEquals(StableIds.of("a", "b"), StableIds.of("b", "a"))
        assertTrue(StableIds.of("fixture").matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun corpusRoundTripKeepsSourceAndUnknownConfidence() {
        val expected = ExpectedObservation("obs", Analyte.SODIUM, "Sódio", "1,234", "mmol/L", "10–20",
            null, "10", "20", ExpectedStatus.NEEDS_REVIEW, "AMBIGUOUS_INPUT", true,
            listOf(ExtractedFieldEvidence("resultRaw", "1,234",
                SourceRegion(1, 10.0, 20.0, 60.0, 40.0, 600.0, 800.0), "span", "SYNTHETIC", "1")))
        val truth = GroundTruthVersion(caseId = "case", version = "1", reviewState = ReviewState.REVIEWED,
            reviewerId = "fixture-author", reviewedAt = "2026-10-21T00:00:00Z", observations = listOf(expected))
        val decoded = json.decodeFromString<GroundTruthVersion>(json.encodeToString(truth))
        assertEquals(truth, decoded)
        assertEquals("1,234", decoded.observations.single().resultRaw)
        assertNull(decoded.observations.single().evidence.single().confidence)
    }

    @Test fun runCheckpointAndComparisonRetainExecutionIdentity() {
        val run = ProcessingRun("run-2", "doc", "a".repeat(64), "case", ExtractionMode.BASELINE,
            "ocr-1", "parser-1", "REF_NUMERIC/2.0.0", RunState.PARTIAL, "2026-10-21T00:00:00Z",
            "synthetic-device", "synthetic-api", parentRunId = "run-1")
        assertEquals(run, json.decodeFromString<ProcessingRun>(json.encodeToString(run)))
        val checkpoint = PageCheckpoint(run.runId, 1, run.mode, PageState.COMPLETE, 32)
        assertEquals(checkpoint, json.decodeFromString<PageCheckpoint>(json.encodeToString(checkpoint)))
        val comparison = Comparison("cmp", run.runId, "case", "truth-1", "b".repeat(64),
            "2026-10-21T00:00:00Z", listOf(FieldDifference("obs", "unitRaw", "mg/dL", null)),
            listOf("missing"), listOf("extra"))
        assertEquals(comparison, json.decodeFromString<Comparison>(json.encodeToString(comparison)))
    }

    @Test fun manifestRoundTripPreservesFamilyAndProvenance() {
        val item = CorpusCase("case", "family", CorpusOrigin.PUBLIC_DEMONSTRATION, CorpusFormat.PDF_TEXT,
            CorpusPartition.DEVELOPMENT, "layout", "1", "documents/a.pdf", "a".repeat(64), 10,
            "truth/a.json", "b".repeat(64), provenance = PublicProvenance(
                "https://example.org/demonstration.pdf", "2026-10-21", "demonstration only", false, false))
        val manifest = CorpusManifest(corpusVersion = "1", generatorVersion = "1", seed = 7,
            fontSha256 = "c".repeat(64), cases = listOf(item))
        assertEquals(manifest, json.decodeFromString<CorpusManifest>(json.encodeToString(manifest)))
    }

    @Test fun schemaDoesNotSilentlyIgnoreUnknownKeys() {
        assertThrows(Exception::class.java) { json.decodeFromString<PageCheckpoint>(
            """{"runId":"run","page":1,"mode":"BASELINE","state":"COMPLETE","durationMillis":1,"unexpected":true}""") }
    }

    @Test fun generatedPythonPackageUsesTheJvmWireContract() {
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/validation/$name"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val manifest = json.decodeFromString<CorpusManifest>(resource("manifest.json"))
        val truth = json.decodeFromString<GroundTruthVersion>(resource("truth.json"))
        assertEquals(manifest.cases.single().caseId, truth.caseId)
        assertEquals(5, truth.observations.size)
        assertEquals(setOf(Analyte.GLUCOSE, Analyte.UREA, Analyte.CREATININE, Analyte.SODIUM, Analyte.POTASSIUM),
            truth.observations.map { it.analyte }.toSet())
        assertEquals(StableIds.of("family", "within"), manifest.cases.single().familyId)
        assertEquals(truth, json.decodeFromString<GroundTruthVersion>(json.encodeToString(truth)))
    }

    @Test fun invalidOrNonFiniteSourceGeometryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SourceRegion(0, 0.0, 0.0, 1.0, 1.0, 10.0, 10.0) }
        assertThrows(IllegalArgumentException::class.java) { SourceRegion(1, Double.NaN, 0.0, 1.0, 1.0, 10.0, 10.0) }
        assertThrows(IllegalArgumentException::class.java) { SourceRegion(1, 0.0, 0.0, 20.0, 1.0, 10.0, 10.0) }
    }
}
