package br.com.exames.datalocal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.datadocuments.SafDocumentReader
import br.com.exames.domain.model.Document
import br.com.exames.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.util.UUID

/** Synthetic bytes only. The provider deliberately lies about SIZE and can revoke access. */
class SyntheticImportProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?) = MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply {
        addRow(arrayOf(size))
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (revoked) throw FileNotFoundException("SYNTHETIC_REVOKED")
        val pipe = ParcelFileDescriptor.createPipe()
        val snapshot = bytes
        val repeatedCount = oversizedBytes
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    if (repeatedCount == 0) out.write(snapshot)
                    else {
                        val chunk = ByteArray(8192)
                        var remaining = repeatedCount
                        while (remaining > 0) {
                            val count = minOf(chunk.size, remaining)
                            out.write(chunk, 0, count)
                            remaining -= count
                        }
                    }
                }
            }
            catch (_: java.io.IOException) { }
        }.start()
        return pipe[0]
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object {
        var bytes = "%PDF-1.4\nSYNTHETIC-IMPORT-PRIVATE-MARKER\n%%EOF\n".toByteArray()
        var mime: String? = "application/pdf"
        var size: Long? = null
        var revoked = false
        var oversizedBytes = 0
    }
}

@RunWith(AndroidJUnit4::class)
class ProtectedDocumentImporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val artifacts = EncryptedArtifactStore(context)
    private val uri = Uri.parse("content://br.com.exames.datalocal.test.synthetic-import/document")
    private val ids = mutableListOf<String>()
    private class Repository : DocumentRepository {
        val rows = mutableMapOf<String, Document>()
        var rejectSave = false
        var unavailable = false
        override fun observeDocuments() = MutableStateFlow(rows.values.toList())
        override suspend fun find(id: String): Document? {
            check(!unavailable) { "SYNTHETIC_DB_UNAVAILABLE" }
            return rows[id]
        }
        override suspend fun save(document: Document) {
            check(!rejectSave) { "SYNTHETIC_SAVE_FAILED" }; rows[document.id] = document
        }
        override suspend fun deleteCompletely(id: String) { rows.remove(id) }
    }
    private val repository = Repository()
    private fun importer() = ProtectedDocumentImporter(context, SafDocumentReader(context.contentResolver), artifacts, repository)
    private fun marker(id: String) = File(context.noBackupFilesDir, "pending-imports/$id")
    private fun newId() = UUID.randomUUID().toString().also { ids += it }

    @After fun cleanup() {
        (ids + repository.rows.keys).forEach { artifacts.deleteDocument(it); marker(it).delete() }
        File(context.noBackupFilesDir, "pending-imports").listFiles()?.forEach {
            artifacts.deleteDocument(it.name); it.delete()
        }
        SyntheticImportProvider.bytes = "%PDF-1.4\nSYNTHETIC-IMPORT-PRIVATE-MARKER\n%%EOF\n".toByteArray()
        SyntheticImportProvider.mime = "application/pdf"
        SyntheticImportProvider.size = null
        SyntheticImportProvider.revoked = false
        SyntheticImportProvider.oversizedBytes = 0
    }

    @Test fun actualSourceSurvivesRevocationAndReopeningWithoutPlaintextFiles() = runBlocking {
        SyntheticImportProvider.size = 1 // The source is larger; SIZE must not be used as truth.
        val document = importer().import(uri)
        assertEquals("artifact:original", document.uriLocal)
        assertEquals(64, document.hash.length)
        SyntheticImportProvider.revoked = true
        assertArrayEquals(SyntheticImportProvider.bytes, artifacts.read(document.id, "original"))
        importer().reconcile()
        assertEquals(document, repository.find(document.id))
        val encrypted = File(context.noBackupFilesDir, "artifacts/${document.id}/original.enc").readBytes()
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("SYNTHETIC-IMPORT-PRIVATE-MARKER"))
        assertFalse(marker(document.id).exists())
    }
    @Test fun unknownSizeAndGenericMimeUseRealSignature() = runBlocking {
        SyntheticImportProvider.size = null
        SyntheticImportProvider.mime = "application/octet-stream"
        assertEquals("application/pdf", importer().import(uri).mimeType)
    }
    @Test fun mismatchTruncationAndRevokedUriPublishNothing() = runBlocking {
        SyntheticImportProvider.mime = "image/png"
        reject { importer().import(uri) }
        SyntheticImportProvider.mime = "application/pdf"
        SyntheticImportProvider.bytes = "%PDF-1.4\nTRUNCATED".toByteArray()
        reject { importer().import(uri) }
        SyntheticImportProvider.revoked = true
        reject { importer().import(uri) }
        assertTrue(repository.rows.isEmpty())
    }
    @Test fun lyingSizeCannotBypassActualByteLimit() = runBlocking {
        SyntheticImportProvider.size = 1
        SyntheticImportProvider.oversizedBytes = br.com.exames.datadocuments.DocumentLimits.MAX_BYTES + 1
        try {
            importer().import(uri)
            fail("Accepted byte overflow")
        } catch (e: IllegalArgumentException) {
            assertEquals("DOCUMENT_TOO_LARGE", e.message)
        }
        assertTrue(repository.rows.isEmpty())
    }
    @Test fun saveFailureRemovesEncryptedSourceAndJournal() = runBlocking {
        repository.rejectSave = true
        reject { importer().import(uri) }
        assertTrue(repository.rows.isEmpty())
        assertTrue(File(context.noBackupFilesDir, "pending-imports").listFiles().orEmpty().isEmpty())
    }
    @Test fun processInterruptionReclaimsOrphansButPreservesCommittedSource() = runBlocking {
        val orphan = newId()
        marker(orphan).parentFile!!.mkdirs(); marker(orphan).createNewFile()
        artifacts.write(orphan, "original", SyntheticImportProvider.bytes.inputStream())
        File(context.noBackupFilesDir, "artifacts/$orphan/pending-synthetic.enc").writeBytes(byteArrayOf(1, 2, 3))
        val committed = newId()
        marker(committed).createNewFile()
        artifacts.write(committed, "original", SyntheticImportProvider.bytes.inputStream())
        repository.rows[committed] = Document(committed, "artifact:original", "a".repeat(64), "application/pdf", Instant.now(), null)
        importer().reconcile()
        assertFalse(File(context.noBackupFilesDir, "artifacts/$orphan").exists())
        assertArrayEquals(SyntheticImportProvider.bytes, artifacts.read(committed, "original"))
        assertFalse(marker(committed).exists())
    }
    @Test fun unavailableDatabasePreservesPendingEncryptedSourceForRetry() = runBlocking {
        val id = newId()
        marker(id).parentFile!!.mkdirs(); marker(id).createNewFile()
        artifacts.write(id, "original", SyntheticImportProvider.bytes.inputStream())
        repository.unavailable = true
        reject { importer().reconcile() }
        assertTrue(marker(id).exists())
        assertArrayEquals(SyntheticImportProvider.bytes, artifacts.read(id, "original"))
        repository.unavailable = false
        importer().reconcile()
        assertFalse(marker(id).exists())
        assertFalse(File(context.noBackupFilesDir, "artifacts/$id").exists())
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }
}
