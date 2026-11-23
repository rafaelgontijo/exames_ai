package br.com.exames.app

import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.EncryptedArtifactStore
import br.com.exames.datalocal.EncryptedExamHistoryStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs in the same disposable package after replacing a previous APK installation. */
class ExtractionUpgradeTest {
    @Test fun replacementPreservesLegacyDatabaseAndArtifact() = runBlocking {
        val registry = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("extractionUpgrade") == "true")
        val context = registry.targetContext
        check(context.packageName == "br.com.exames.offline.validation.extraction.upgrade")
        val id = "current-exam-upgrade-control"
        val legacy = EncryptedArtifactStore(context)
        val database = context.getDatabasePath("exames.db")
        check(database.isFile)
        fun databaseDigest(): ByteArray = database.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
            digest.digest()
        }
        val hash = databaseDigest()
        val store = EncryptedExamHistoryStore(context)
        check(store.list().isEmpty())
        val currentId = store.accept(ExamContractProvider.uri(registry.context, "transport"))
        check(File(context.noBackupFilesDir, "artifacts/$currentId/original.enc").isFile)
        store.delete(currentId)
        check(store.list().isEmpty())
        check(hash.contentEquals(databaseDigest()))
        check(legacy.read(id, "original").contentEquals("opaque transport control".encodeToByteArray()))
    }
}
