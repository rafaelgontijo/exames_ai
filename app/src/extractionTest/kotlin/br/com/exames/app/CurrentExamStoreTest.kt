package br.com.exames.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.CurrentExamStore
import br.com.exames.datalocal.EncryptedArtifactStore
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
class CurrentExamStoreTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test fun currentReopensAndDiscardLeavesExistingLegacyArtifactAlone() = runBlocking {
        val artifacts = EncryptedArtifactStore(target)
        val store = CurrentExamStore(target)
        store.discard()
        val legacy = UUID.randomUUID().toString()
        val legacyBytes = byteArrayOf(4, 5, 6)
        artifacts.write(legacy, "original", legacyBytes.inputStream())
        try {
            val id = store.accept(ExamContractProvider.uri(instrumentation.context, "transport"))
            val interrupted = CurrentExamStore(target).current()
            assertEquals(id, interrupted?.id)
            assertNull(interrupted?.extraction)
            store.save(id, ExamExtraction(listOf(ExamPageOutcome(1, emptyList(), emptySet(), emptySet()))))

            val idFile = File(target.noBackupFilesDir, "current-exam")
            val owned = File(target.noBackupFilesDir, "artifacts/$id")
            assertEquals(id, idFile.readText())
            assertEquals(setOf("original.enc", "ai-extraction-v1.enc"), owned.listFiles()!!.map { it.name }.toSet())
            assertFalse(File(owned, "original.enc").readBytes().decodeToString().contains("%PDF-"))
            assertFalse(File(owned, "ai-extraction-v1.enc").readBytes().decodeToString().contains("pages"))
            val reopened = CurrentExamStore(target).current()!!
            assertEquals(1, reopened.extraction!!.pages.single().page)
            assertFalse(reopened.extraction!!.partial)

            val replacement = store.accept(ExamContractProvider.uri(instrumentation.context, "transport"))
            assertNotEquals(id, replacement)
            assertFalse(owned.exists())
            store.discard()
            assertNull(CurrentExamStore(target).current())
            assertFalse(File(target.noBackupFilesDir, "artifacts/$replacement").exists())
            assertTrue(artifacts.read(legacy, "original").contentEquals(legacyBytes))
        } finally {
            store.discard()
            artifacts.deleteDocument(legacy)
        }
    }

    @Test fun emptyAndUnsupportedInputsKeepPreviousResult() = runBlocking {
        val store = CurrentExamStore(target)
        store.discard()
        try {
            val id = store.accept(ExamContractProvider.uri(instrumentation.context, "transport"))
            for ((kind, issue) in listOf("empty" to ExamInputIssue.EMPTY, "wrong-mime" to ExamInputIssue.NOT_PDF,
                "garbage" to ExamInputIssue.NOT_PDF)) {
                val failure = runCatching { store.accept(ExamContractProvider.uri(instrumentation.context, kind)) }.exceptionOrNull()
                assertEquals(issue, (failure as? ExamInputException)?.issue)
                assertEquals(id, store.current()?.id)
            }
        } finally {
            store.discard()
        }
    }
}
