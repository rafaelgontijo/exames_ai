package br.com.exames.datalocal

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class ProtectedDatabaseTest {
    private lateinit var db: ProtectedDatabase
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val codec = EncryptedPayloadCodec { key }
    private fun payload(type: String, id: String) = codec.encrypt(type, id, "2", "SINTÉTICO".toByteArray())

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
        ProtectedDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test fun outputsAreImmutableAndDeletionCascadesButKeepsOutbox() = runBlocking {
        val dao = db.records()
        dao.insertDocument(ProtectedDocumentRow("doc", 1, payload("document", "doc")))
        dao.insertRun(ProtectedRunRow("run", "doc", "COMPLETE", 1, payload("run", "run")))
        val observation = ProtectedObservationRow("obs", "doc", "run", 1, 0, payload("observation", "obs"))
        dao.insertObservations(listOf(observation))
        dao.insertReference(ProtectedReferenceRow("obs", payload("reference", "obs")))
        dao.insertAssessment(ProtectedAssessmentRow("assessment", "obs", "run", payload("assessment", "assessment")))
        dao.appendCorrection(ProtectedCorrectionRow("correction", "obs", payload("correction", "correction")))
        dao.appendGroundTruth(ProtectedGroundTruthRow("truth", "doc", "1", payload("truth", "truth")))
        dao.appendComparison(ProtectedComparisonRow("comparison", "doc", "run", "truth", payload("comparison", "comparison")))
        dao.checkpoint(ProtectedPageRow("run", 1, "COMPLETE", payload("checkpoint", "page-1")))
        try { dao.insertObservations(listOf(observation)); fail("Original output must not be replaced") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertArrayEquals(observation.payload, dao.observations("run").single().payload)
        dao.deleteCompletely("doc")
        assertNull(dao.document("doc"))
        assertTrue(dao.runs("doc").isEmpty())
        assertTrue(dao.observations("run").isEmpty())
        assertNull(dao.reference("obs"))
        assertTrue(dao.assessments("run").isEmpty())
        assertTrue(dao.corrections("obs").isEmpty())
        assertTrue(dao.groundTruth("doc").isEmpty())
        assertTrue(dao.comparisons("run").isEmpty())
        assertTrue(dao.checkpoints("run").isEmpty())
        assertEquals("doc", dao.pendingDeletions().single().documentId)
    }

    @Test fun observationsCannotBeAttachedToAnotherRunsDocument() = runBlocking {
        val dao = db.records()
        dao.insertDocument(ProtectedDocumentRow("doc-a", 1, payload("document", "doc-a")))
        dao.insertDocument(ProtectedDocumentRow("doc-b", 2, payload("document", "doc-b")))
        dao.insertRun(ProtectedRunRow("run", "doc-a", "COMPLETE", 1, payload("run", "run")))
        try {
            dao.insertObservations(listOf(ProtectedObservationRow("obs", "doc-b", "run", 1, 0, payload("observation", "obs"))))
            fail("Cross-document association must fail")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertTrue(dao.observations("run").isEmpty())
    }
}
