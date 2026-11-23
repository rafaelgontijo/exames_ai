package br.com.exames.domain.extraction

import br.com.exames.domain.validation.SourceRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisionExamProtocolTest {
    private val region0 = SourceRegion(1, 0.0, 0.0, 1000.0, 420.0, 1000.0, 1000.0)
    private val region1 = SourceRegion(1, 0.0, 320.0, 1000.0, 740.0, 1000.0, 1000.0)
    private val band0 = VisionPageBand(1, 0, byteArrayOf(1, 2, 3), "image/png", region0)

    @Test fun buildsNeutralPromptWithoutConcreteClinicalValues() {
        val req = VisionExamProtocol.request(band0)
        assertEquals(VisionExamProtocol.PROMPT_VERSION, req.promptVersion)
        assertEquals(VisionExamProtocol.SCHEMA_VERSION, req.schemaVersion)
        assertEquals("image/png", req.imageMimeType)
        assertFalse(req.instruction.contains("Hemoglobina"))
        assertFalse(req.instruction.contains("14,2"))
        assertTrue(req.instruction.contains("<exact test name 1>"))
    }

    @Test fun decodesStrictJsonAndRejectsFencesWrongSchemaExtraKeysAndOverflow() {
        val valid = """{"schema":"exam-vision-items/1","items":[{"name":"Glicose","result":"82","unit":"mg/dL","reference":"70 a 99 mg/dL","needs_review":false},{"name":"PCR","result":"< 0,5","unit":"mg/L","reference":null,"needs_review":true}]}"""
        val decoded = VisionExamProtocol.decode(band0, valid)
        assertNotNull(decoded)
        assertFalse(decoded!!.hasInvalidItems)
        assertEquals(2, decoded.items.size)
        assertEquals("Glicose", decoded.items[0].name)
        assertEquals("82", decoded.items[0].result)
        assertEquals("mg/dL", decoded.items[0].unit)
        assertEquals("70 a 99 mg/dL", decoded.items[0].reference)
        assertFalse(decoded.items[0].needsReview)
        assertNull(decoded.items[1].reference)
        assertTrue(decoded.items[1].needsReview)

        val rejected = listOf(
            "```json\n$valid\n```",
            valid.replace("exam-vision-items/1", "exam-vision-items/9"),
            valid.replace("\"items\":", "\"extra\":1,\"items\":"),
            valid + " trailing",
            " ".repeat(VisionExamProtocol.MAX_RESPONSE_CHARS + 1),
            """{"schema":"exam-vision-items/1","items":[${(1..65).joinToString(",") { """{"name":"A$it","result":"1","unit":null,"reference":null,"needs_review":false}""" }}]}""",
        )
        rejected.forEach { assertNull(VisionExamProtocol.decode(band0, it), "Expected null for $it") }
    }

    @Test fun dropsInvalidItemsAndEchoedPlaceholdersWithoutErasingValidSiblings() {
        val mixed = """{"schema":"exam-vision-items/1","items":[
            {"name":"<exact test name 1>","result":"<exact printed value 1>","unit":null,"reference":null,"needs_review":false},
            {"name":"Item A","result":123,"unit":null,"reference":null,"needs_review":false},
            {"name":"Item Extra","result":"1,0","unknown":true},
            {"name":"Item B","result":"9,5","unit":"g/dL","reference":"8 a 12 g/dL"}
        ]}"""
        val decoded = VisionExamProtocol.decode(band0, mixed)!!
        assertTrue(decoded.hasInvalidItems)
        assertEquals(1, decoded.items.size)
        assertEquals("Item B", decoded.items.single().name)
        assertFalse(decoded.items.single().needsReview)
    }

    @Test fun deduplicatesOverlapAndFlagsConflictingAdjacentBandValues() {
        val p1 = VisionProposal("Glicose", "82", "mg/dL", null, false, 0, region0)
        val p2 = VisionProposal("glicose", "82", "mg/dL", "70 a 99 mg/dL", false, 1, region1)
        val dedup = VisionExamProtocol.deduplicate(listOf(p1, p2))
        assertFalse(dedup.hasConflicts)
        assertEquals(1, dedup.items.size)
        assertEquals("70 a 99 mg/dL", dedup.items.single().reference)

        val c1 = VisionProposal("Sódio", "140", "mEq/L", "136 a 145 mEq/L", false, 0, region0)
        val c2 = VisionProposal("Sódio", "14", "mEq/L", "136 a 145 mEq/L", false, 1, region1)
        val conflict = VisionExamProtocol.deduplicate(listOf(c1, c2))
        assertTrue(conflict.hasConflicts)
        assertEquals(2, conflict.items.size)
        assertTrue(conflict.items.all { it.needsReview })
    }
}
