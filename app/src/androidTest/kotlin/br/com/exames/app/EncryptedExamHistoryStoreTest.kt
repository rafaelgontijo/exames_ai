package br.com.exames.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.EncryptedArtifactStore
import br.com.exames.datalocal.EncryptedExamHistoryStore
import br.com.exames.datalocal.ExamInputException
import br.com.exames.datalocal.ExamInputIssue
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExamPageOutcome
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedExamHistoryStoreTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test fun storesMultipleExamsMigratesLegacyPointerAndLeavesUnrelatedArtifactAlone() = runBlocking {
        val artifacts = EncryptedArtifactStore(target)
        val store = EncryptedExamHistoryStore(target)
        store.deleteAll()

        val legacyUnrelated = UUID.randomUUID().toString()
        val legacyBytes = byteArrayOf(4, 5, 6)
        artifacts.write(legacyUnrelated, "original", legacyBytes.inputStream())

        // Seed legacy current-exam single pointer to verify seamless upgrade migration.
        val migratedId = UUID.randomUUID().toString()
        val pointer = File(target.noBackupFilesDir, "current-exam")
        artifacts.write(migratedId, "original", "%PDF-1.4\n".encodeToByteArray().inputStream())
        pointer.writeText(migratedId)

        try {
            val migratedList = EncryptedExamHistoryStore(target).list()
            assertEquals(listOf(migratedId), migratedList.map { it.id })
            assertTrue(migratedList.single().interrupted)
            assertFalse(pointer.exists())

            val firstId = store.accept(ExamContractProvider.uri(instrumentation.context, "transport"))
            store.save(firstId, ExamExtraction(listOf(ExamPageOutcome(1, emptyList(), emptySet(), emptySet()))))
            val secondId = store.accept(ExamContractProvider.uri(instrumentation.context, "transport"))
            assertNotEquals(firstId, secondId)

            val firstDir = File(target.noBackupFilesDir, "artifacts/$firstId")
            assertEquals(setOf("original.enc", "ai-extraction-v1.enc"), firstDir.listFiles()!!.map { it.name }.toSet())
            assertFalse(File(firstDir, "original.enc").readBytes().decodeToString().contains("%PDF-"))
            assertFalse(File(firstDir, "ai-extraction-v1.enc").readBytes().decodeToString().contains("pages"))

            // Cancel second exam preserves first and migrated exams.
            store.cancelActive(secondId)
            assertFalse(File(target.noBackupFilesDir, "artifacts/$secondId").exists())
            assertEquals(setOf(firstId, migratedId), store.list().map { it.id }.toSet())

            // Reopen first exam.
            val reopened = EncryptedExamHistoryStore(target).load(firstId)!!
            assertEquals(1, reopened.extraction!!.pages.single().page)

            // Delete all removes managed exams and keeps unrelated legacy artifact intact.
            store.deleteAll()
            assertTrue(store.list().isEmpty())
            assertNull(store.load(firstId))
            assertTrue(artifacts.read(legacyUnrelated, "original").contentEquals(legacyBytes))
        } finally {
            store.deleteAll()
            pointer.delete()
            artifacts.deleteDocument(legacyUnrelated)
        }
    }

    @Test fun emptyAndUnsupportedInputsKeepExistingHistory() = runBlocking {
        val store = EncryptedExamHistoryStore(target)
        store.deleteAll()
        try {
            val id = store.accept(ExamContractProvider.uri(instrumentation.context, "transport"))
            for ((kind, issue) in listOf(
                "empty" to ExamInputIssue.EMPTY,
                "wrong-mime" to ExamInputIssue.NOT_PDF,
                "garbage" to ExamInputIssue.NOT_PDF,
            )) {
                val failure = runCatching { store.accept(ExamContractProvider.uri(instrumentation.context, kind)) }.exceptionOrNull()
                assertEquals(issue, (failure as? ExamInputException)?.issue)
                assertEquals(listOf(id), store.list().map { it.id })
            }
        } finally {
            store.deleteAll()
        }
    }
}
