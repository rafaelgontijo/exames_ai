package br.com.exames.datalocal

import br.com.exames.domain.extraction.ExamCitation
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExamObservation
import br.com.exames.domain.extraction.ExamPageOutcome
import br.com.exames.domain.validation.SourceRegion
import java.io.FileNotFoundException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamHistoryControllerTest {
    @Test fun storesMultipleExamsOrderedNewestFirstAndDeletesIndividually() {
        val backend = FakeBackend()
        var clock = 1_000L
        var seq = 0
        val controller = ExamHistoryController(
            backend = backend,
            newId = { "00000000-0000-0000-0000-00000000000${++seq}" },
            nowMillis = { clock.also { clock += 5_000L } },
        )

        val firstId = controller.acceptBytes(byteArrayOf(1, 2, 3))
        val firstSaved = controller.save(firstId, sampleExtraction("14,2", "g/dL", "13,5 a 17,5 g/dL"))
        assertEquals(1, firstSaved.withinRangeCount)
        assertEquals(0, firstSaved.reviewCount)
        assertFalse(firstSaved.interrupted)

        val secondId = controller.acceptBytes(byteArrayOf(4, 5, 6))
        val secondSaved = controller.save(secondId, sampleExtraction("118", "mg/dL", "70 a 99 mg/dL"))
        assertEquals(1, secondSaved.reviewCount)

        val listed = controller.list()
        assertEquals(listOf(secondId, firstId), listed.map { it.id })
        assertArrayEquals(byteArrayOf(1, 2, 3), controller.readOriginalPdf(firstId))
        assertArrayEquals(byteArrayOf(4, 5, 6), controller.readOriginalPdf(secondId))

        // Cancelling a third in-progress exam preserves existing history.
        val cancelledId = controller.acceptBytes(byteArrayOf(7, 8, 9))
        controller.delete(cancelledId)
        assertEquals(listOf(secondId, firstId), controller.list().map { it.id })
        assertFalse(backend.existsArtifact(cancelledId, ExamHistoryController.ORIGINAL))

        // Deleting first exam leaves second intact.
        controller.delete(firstId)
        assertEquals(listOf(secondId), controller.list().map { it.id })
        assertNull(controller.load(firstId))
        assertEquals("118", controller.load(secondId)?.extraction?.pages?.single()?.observations?.single()?.result)

        // Delete all clears index and documents.
        controller.deleteAll()
        assertTrue(controller.list().isEmpty())
        assertFalse(backend.existsArtifact(secondId, ExamHistoryController.ORIGINAL))
    }

    @Test fun migratesLegacySingleExamPointerWithCompletedAndInterruptedStates() {
        val completedId = "11111111-1111-1111-1111-111111111111"
        val backend = FakeBackend().apply {
            legacyId = completedId
            writeArtifact(completedId, ExamHistoryController.ORIGINAL, byteArrayOf(9, 9))
            val encoded = Json.encodeToString(ExamExtraction.serializer(), sampleExtraction("118", "mg/dL", "70 a 99 mg/dL")).encodeToByteArray()
            writeArtifact(completedId, ExamHistoryController.RESULT, encoded)
        }
        val controller = ExamHistoryController(backend, nowMillis = { 2_000L })

        val entries = controller.list()
        assertEquals(1, entries.size)
        assertEquals(completedId, entries.single().id)
        assertEquals(1, entries.single().reviewCount)
        assertFalse(entries.single().interrupted)
        assertNull(backend.legacyId)

        val interruptedId = "22222222-2222-2222-2222-222222222222"
        val interruptedBackend = FakeBackend().apply {
            legacyId = interruptedId
            writeArtifact(interruptedId, ExamHistoryController.ORIGINAL, byteArrayOf(8, 8))
        }
        val interruptedController = ExamHistoryController(interruptedBackend, nowMillis = { 3_000L })
        val interruptedEntries = interruptedController.list()
        assertEquals(1, interruptedEntries.size)
        assertTrue(interruptedEntries.single().interrupted)
        assertNull(interruptedController.load(interruptedId)?.extraction)
        assertNull(interruptedBackend.legacyId)
    }

    private fun sampleExtraction(value: String, unit: String, ref: String): ExamExtraction {
        val r = SourceRegion(1, 10.0, 20.0, 80.0, 30.0, 500.0, 800.0)
        return ExamExtraction(
            listOf(
                ExamPageOutcome(
                    page = 1,
                    observations = listOf(
                        ExamObservation(
                            name = ExamCitation("n1", "Exame", listOf(r)),
                            sourceResult = ExamCitation("v1", "$value $unit", listOf(r)),
                            result = value,
                            unit = unit,
                            reference = ExamCitation("f1", ref, listOf(r)),
                            referenceNeedsReview = false,
                        ),
                    ),
                    issues = emptySet(),
                    inferenceFailures = emptySet(),
                ),
            ),
        )
    }

    private class FakeBackend : HistoryArtifactBackend {
        private val files = mutableMapOf<Pair<String, String>, ByteArray>()
        private var indexBytes: ByteArray? = null
        var legacyId: String? = null

        override fun writeArtifact(documentId: String, artifactId: String, bytes: ByteArray) {
            files[documentId to artifactId] = bytes.copyOf()
        }

        override fun readArtifact(documentId: String, artifactId: String): ByteArray =
            files[documentId to artifactId]?.copyOf() ?: throw FileNotFoundException("$documentId/$artifactId")

        override fun existsArtifact(documentId: String, artifactId: String): Boolean =
            (documentId to artifactId) in files

        override fun deleteDocument(documentId: String) {
            files.keys.removeAll { it.first == documentId }
        }

        override fun readIndex(): ByteArray? = indexBytes?.copyOf()

        override fun writeIndex(bytes: ByteArray) {
            indexBytes = bytes.copyOf()
        }

        override fun readLegacyCurrentId(): String? = legacyId

        override fun deleteLegacyCurrentPointer() {
            legacyId = null
        }
    }
}
