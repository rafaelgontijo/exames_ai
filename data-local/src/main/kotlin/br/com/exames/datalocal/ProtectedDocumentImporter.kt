package br.com.exames.datalocal

import android.content.Context
import android.net.Uri
import br.com.exames.datadocuments.SafDocumentReader
import br.com.exames.domain.model.Document
import br.com.exames.domain.repository.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/** Commit private source before publishing metadata. Journals contain only opaque document IDs. */
class ProtectedDocumentImporter(
    context: Context,
    private val reader: SafDocumentReader,
    private val artifacts: EncryptedArtifactStore,
    private val documents: DocumentRepository,
) {
    private val journal = File(context.noBackupFilesDir, "pending-imports")

    suspend fun import(uri: Uri): Document = persist(reader.readValidated(uri))

    /**
     * Confirmed camera capture: validated bytes enter the same journal/encryption/commit path as a picked file, with no
     * simulated URI and no plaintext fallback. The page is wiped on every outcome.
     */
    suspend fun importCapture(page: br.com.exames.datadocuments.NormalizedPage): Document = try {
        persist(br.com.exames.datadocuments.CapturedDocuments.validate(page))
    } finally {
        page.wipe()
    }

    suspend fun importCase(case: br.com.exames.domain.validation.CorpusPackage.Case): Document {
        val mime = when (case.identity.format) {
            br.com.exames.domain.validation.CorpusFormat.PDF_TEXT, br.com.exames.domain.validation.CorpusFormat.PDF_SCANNED -> "application/pdf"
            br.com.exames.domain.validation.CorpusFormat.PNG -> "image/png"
            br.com.exames.domain.validation.CorpusFormat.JPEG -> "image/jpeg"
        }
        return persist(br.com.exames.datadocuments.ValidatedDocument(case.document, mime, case.identity.documentSha256), case)
    }
    private suspend fun persist(source: br.com.exames.datadocuments.ValidatedDocument,
        case: br.com.exames.domain.validation.CorpusPackage.Case? = null): Document = withContext(Dispatchers.IO) {
        lock.withLock {
            reconcileLocked()
            try {
                val id = UUID.randomUUID().toString()
                check(journal.isDirectory || journal.mkdirs())
                val marker = File(journal, id)
                FileOutputStream(marker).use { it.fd.sync() }
                try {
                    // No suspension between source commit and metadata publication. Cancellation
                    // during provider reading still closes its stream and publishes nothing.
                    withContext(NonCancellable) {
                        artifacts.write(id, "original", source.bytes.inputStream())
                        val document = Document(id, "artifact:original", source.sha256,
                            source.mimeType, Instant.now(), null, corpusCase = case?.identity)
                        if (case == null) documents.save(document)
                        else requireNotNull(documents as? br.com.exames.domain.validation.ValidationRepository)
                            .importCorpusDocument(document, case.truth)
                        marker.delete() // If interrupted here, reconciliation preserves the committed source.
                        document
                    }
                } catch (failure: Exception) {
                    // A failed DB call may have an uncertain outcome: consult it before deleting.
                    // If DB/key access is unavailable, retain the encrypted source and journal.
                    withContext(NonCancellable) { reconcileLocked() }
                    throw failure
                }
            } finally {
                source.bytes.fill(0)
            }
        }
    }

    /** Call on reopening import, before accepting another source. Safe to repeat after process death. */
    suspend fun reconcile() = withContext(Dispatchers.IO) { lock.withLock { reconcileLocked() } }

    private suspend fun reconcileLocked() {
        if (!journal.exists()) return
        val markers = checkNotNull(journal.listFiles())
        for (marker in markers) {
            require(marker.isFile && UUID_PATTERN.matches(marker.name)) { "IMPORT_JOURNAL_INVALID" }
            if (documents.find(marker.name) == null) artifacts.deleteDocument(marker.name)
            check(marker.delete()) { "IMPORT_CLEANUP_FAILED" }
        }
    }

    companion object {
        // Also serializes independently constructed instances in this application process.
        private val lock = Mutex()
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
