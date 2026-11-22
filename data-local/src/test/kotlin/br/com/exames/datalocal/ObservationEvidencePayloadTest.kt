package br.com.exames.datalocal

import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ObservationEvidencePayloadTest {
    private val original = Observation("obs", "doc", "Glicose", "15", "mg/dL", 1, "SYNTHETIC SOURCE")
    @Test fun oldPayloadWithoutEvidenceStillReadsWithExplicitEmptyLists() {
        val json = ModelPayloads.observation(original)
        val legacy = JsonObject(json.filterKeys { it !in setOf("fieldEvidence", "referenceCandidates", "referenceRaw") })
        assertEquals(original, ModelPayloads.observation(legacy))
    }
    @Test fun preservesFieldSourceAndCompetingReferencesWithoutSelectingOne() {
        val evidence = ExtractedFieldEvidence("resultRaw", "15", SourceRegion(1, 10.0, 20.0, 30.0, 40.0, 600.0, 800.0), "span-1", "PDF_TEXT", "synthetic/1")
        val references = listOf(
            ReferenceRange("obs", "10–20 mg/dL", "mg/dL", 1, applicability = Applicability.UNKNOWN),
            ReferenceRange("obs", "30–40 mg/dL", "mg/dL", 1, applicability = Applicability.UNKNOWN))
        val value = original.copy(fieldEvidence = listOf(evidence), referenceCandidates = references,
            ambiguities = setOf(Ambiguity.REFERENCE_ASSOCIATION), referenceRaw = "10–20 mg/dL ou 30–40 mg/dL")
        val restored = ModelPayloads.observation(ModelPayloads.observation(value))
        assertEquals(value, restored)
        assertEquals(2, restored.referenceCandidates.size)
        assertNull(restored.fieldEvidence.single().confidence)
        assertFalse(restored.confirmed)
    }
}
