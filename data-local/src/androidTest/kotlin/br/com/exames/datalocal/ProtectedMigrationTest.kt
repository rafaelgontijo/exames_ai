package br.com.exames.datalocal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.ValidationJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProtectedMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun withStore(body: suspend (String, String, AndroidPayloadKeys) -> Unit) = runBlocking {
        val name = "synthetic-${UUID.randomUUID()}.db"
        val alias = "synthetic-${UUID.randomUUID()}"
        try { body(name, alias, AndroidPayloadKeys(alias)) }
        finally {
            for (suffix in listOf("", ".protected-staging", ".legacy-backup")) context.deleteDatabase(name + suffix)
            File(context.noBackupFilesDir, "$name.protected-migration").delete()
            File(context.noBackupFilesDir, "$name.protected-migration.tmp").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    private suspend fun legacy(name: String) {
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().useDatabase { old ->
            old.documentDao().upsert(DocumentEntity("doc", "synthetic-private-uri", "a".repeat(64),
                "application/pdf", Instant.ofEpochMilli(1234), 1, ProcessingState.COMPLETED))
            old.observationDao().upsert(listOf(ObservationEntity("obs", "doc", "SINTÉTICO-MARCADOR", null,
                "15,0", "15.0".toBigDecimal(), "u/L", null, 1, "SINTÉTICO-FONTE", BoundingBox(1, 2, 30, 40),
                null, ResultComparator.EQ, emptySet(), true)))
            old.observationDao().upsertReference(ReferenceRangeEntity("obs", "10".toBigDecimal(), "20".toBigDecimal(),
                ReferenceComparator.RANGE, true, true, "u/L", "10–20 u/L", null, 1, Applicability.APPLICABLE))
            old.observationDao().upsertAssessment(AssessmentEntity("obs", AssessmentStatus.WITHIN_RANGE,
                ReasonCode.INSIDE_REFERENCE, Instant.ofEpochMilli(2345), "REF_NUMERIC/1.0.0"))
            old.observationDao().appendCorrection(ManualCorrectionEntity("correction", "obs", CorrectionField.RESULT,
                "14,0", "15,0", Instant.ofEpochMilli(3456)))
            old.documentDao().enqueueCompleteDeletion("deleted-document")
        }
    }

    private suspend fun verifyMigrated(db: ProtectedDatabase, codec: EncryptedPayloadCodec) {
        val dao = db.records()
        val document = requireNotNull(dao.document("doc"))
        assertEquals(1234L, document.importedAt)
        fun decode(type: String, id: String, payload: ByteArray) =
            ValidationJson.format.parseToJsonElement(codec.decrypt(type, id, "2", payload).toString(Charsets.UTF_8)).jsonObject
        assertEquals("synthetic-private-uri", decode("document", "doc", document.payload).getValue("uriLocal").jsonPrimitive.content)
        val run = dao.runs("doc").single()
        assertEquals("REF_NUMERIC/1.0.0", decode("run", run.id, run.payload).getValue("ruleVersion").jsonPrimitive.content)
        val observation = dao.observations(run.id).single()
        assertEquals("obs", observation.id)
        val value = decode("observation", "obs", observation.payload)
        assertEquals("15,0", value.getValue("resultRaw").jsonPrimitive.content)
        assertEquals("15.0", value.getValue("numericValue").jsonPrimitive.content)
        assertEquals(1, value.getValue("confirmed").jsonPrimitive.int)
        assertEquals("10–20 u/L", decode("reference", "obs", requireNotNull(dao.reference("obs")).payload)
            .getValue("referenceRaw").jsonPrimitive.content)
        assertEquals("REF_NUMERIC/1.0.0", decode("assessment", "obs", dao.assessments(run.id).single().payload)
            .getValue("ruleVersion").jsonPrimitive.content)
        assertEquals("14,0", decode("correction", "correction", dao.corrections("obs").single().payload)
            .getValue("oldValue").jsonPrimitive.content)
        assertEquals("deleted-document", dao.pendingDeletions().single().documentId)
        assertTrue(dao.groundTruth("doc").isEmpty())
    }

    @Test fun migrationPreservesRecordsAndRemovesPlaintextFiles() = withStore { name, _, keys ->
        legacy(name)
        val factory = ProtectedDatabaseFactory(context, name, keys)
        factory.open().useDatabase {
            verifyMigrated(it, factory.codec)
            val repository = ProtectedValidationRepository(factory)
            val legacyRun = br.com.exames.domain.validation.StableIds.of("legacy-run", "doc")
            assertEquals("15,0", repository.observations(legacyRun).single().resultRaw)
            assertTrue(repository.observations(legacyRun).single().confirmed)
            assertEquals("REF_NUMERIC/1.0.0", repository.assessments(legacyRun).single().ruleVersion)
            assertEquals("14,0", repository.corrections("obs").single().oldValue)
        }
        assertFalse(context.getDatabasePath("$name.legacy-backup").exists())
        assertFalse(context.getDatabasePath("$name.protected-staging").exists())
        assertFalse(File(context.noBackupFilesDir, "$name.protected-migration").exists())
        for (file in context.getDatabasePath(name).parentFile!!.listFiles().orEmpty().filter { it.name.startsWith(name) }) {
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("SINTÉTICO-MARCADOR"))
        }
        val reopen = ProtectedDatabaseFactory(context, name, keys)
        reopen.open().useDatabase { verifyMigrated(it, reopen.codec) }
    }

    @Test fun interruptionAtCopyAndBothRenamesIsRecoverable() = runBlocking {
        for (step in ProtectedMigrationStep.entries) {
            withStore { name, _, keys ->
                legacy(name)
                val interrupted = ProtectedDatabaseFactory(context, name, keys) { at ->
                    if (at == step) throw IllegalStateException("Injected synthetic interruption")
                }
                try { interrupted.open().close(); fail("Interruption hook did not run") }
                catch (error: IllegalStateException) { assertEquals("Injected synthetic interruption", error.message) }
                val recovered = ProtectedDatabaseFactory(context, name, keys)
                recovered.open().useDatabase { verifyMigrated(it, recovered.codec) }
                assertFalse(context.getDatabasePath("$name.legacy-backup").exists())
            }
        }
    }

    @Test fun lostKeyDoesNotCreateAnEmptyReplacementStore() = withStore { name, alias, keys ->
        val factory = ProtectedDatabaseFactory(context, name, keys)
        factory.open().useDatabase { it.records().insertDocument(ProtectedDocumentRow("doc", 1,
            factory.codec.encrypt("document", "doc", "2", "{}".toByteArray()))) }
        val original = context.getDatabasePath(name).readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        try { ProtectedDatabaseFactory(context, name, keys).open().close(); fail("Lost key was ignored") }
        catch (_: PayloadKeyUnavailableException) { }
        assertArrayEquals(original, context.getDatabasePath(name).readBytes())
        assertFalse(keys.exists())
    }
}
