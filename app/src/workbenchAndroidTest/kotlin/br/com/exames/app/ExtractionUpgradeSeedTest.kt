package br.com.exames.app

import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.EncryptedArtifactStore
import br.com.exames.datalocal.ProtectedDatabaseFactory
import br.com.exames.datalocal.ProtectedValidationRepository
import br.com.exames.domain.model.Document
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Before an in-place update, creates a genuine legacy record with opaque transport bytes. */
class ExtractionUpgradeSeedTest {
    @Test fun seedProtectedRecord() = runBlocking {
        val registry = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("extractionUpgrade") == "true")
        val context = registry.targetContext
        check(context.packageName == "br.com.exames.offline.validation.extraction.upgrade")
        val id = "current-exam-upgrade-control"
        val bytes = "opaque transport control".encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val factory = ProtectedDatabaseFactory(context)
        try {
            val repository = ProtectedValidationRepository(factory)
            check(repository.find(id) == null)
            EncryptedArtifactStore(context).write(id, "original", bytes.inputStream())
            repository.save(Document(id, "artifact:original", hash, "application/pdf", Instant.EPOCH, null))
            check(repository.find(id)?.hash == hash)
        } finally {
            factory.open().close()
        }
    }
}
