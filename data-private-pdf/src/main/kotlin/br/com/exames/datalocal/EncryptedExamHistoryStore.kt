package br.com.exames.datalocal

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import br.com.exames.datadocuments.AuthenticatedPdfSource
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.datadocuments.SafDocumentReader
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExamSummaryProjection
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Non-identifying metadata stored inside the AES-GCM encrypted history index. */
@Serializable
data class ExamHistoryEntry(
    val id: String,
    val importedAtEpochMillis: Long,
    val pageCount: Int,
    val totalCount: Int,
    val reviewCount: Int,
    val withinRangeCount: Int,
    val unclassifiableCount: Int,
    val hasPageIssues: Boolean,
    val interrupted: Boolean = false,
)

@Serializable
internal data class ExamHistoryIndex(
    val version: Int = 1,
    val entries: List<ExamHistoryEntry> = emptyList(),
)

data class StoredExamRecord(
    val entry: ExamHistoryEntry,
    val extraction: ExamExtraction?,
)

internal interface HistoryArtifactBackend {
    fun writeArtifact(documentId: String, artifactId: String, bytes: ByteArray)
    fun readArtifact(documentId: String, artifactId: String): ByteArray
    fun existsArtifact(documentId: String, artifactId: String): Boolean
    fun deleteDocument(documentId: String)
    fun readIndex(): ByteArray?
    fun writeIndex(bytes: ByteArray)
    fun readLegacyCurrentId(): String?
    fun deleteLegacyCurrentPointer()
}

internal class ExamHistoryController(
    private val backend: HistoryArtifactBackend,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<ExamHistoryEntry> {
        val index = loadAndMigrateIndex()
        return index.entries.sortedByDescending { it.importedAtEpochMillis }
    }

    fun hasExam(id: String): Boolean = loadAndMigrateIndex().entries.any { it.id == id }

    fun load(id: String): StoredExamRecord? {
        val entry = loadAndMigrateIndex().entries.firstOrNull { it.id == id } ?: return null
        val bytes = try {
            backend.readArtifact(id, RESULT)
        } catch (_: FileNotFoundException) {
            return StoredExamRecord(entry.copy(interrupted = true), null)
        }
        val extraction = try {
            json.decodeFromString<ExamExtraction>(bytes.decodeToString())
        } finally {
            bytes.fill(0)
        }
        return StoredExamRecord(entry, extraction)
    }

    fun readOriginalPdf(id: String): ByteArray {
        check(hasExam(id)) { "Exame não encontrado no histórico" }
        return backend.readArtifact(id, ORIGINAL)
    }

    fun acceptBytes(pdfBytes: ByteArray): String {
        val index = loadAndMigrateIndex()
        val id = newId()
        backend.writeArtifact(id, ORIGINAL, pdfBytes)
        val pending = ExamHistoryEntry(
            id = id,
            importedAtEpochMillis = nowMillis(),
            pageCount = 0,
            totalCount = 0,
            reviewCount = 0,
            withinRangeCount = 0,
            unclassifiableCount = 0,
            hasPageIssues = false,
            interrupted = true,
        )
        persistIndex(index.copy(entries = listOf(pending) + index.entries.filterNot { it.id == id }))
        return id
    }

    fun save(id: String, extraction: ExamExtraction): ExamHistoryEntry {
        val index = loadAndMigrateIndex()
        val existing = index.entries.firstOrNull { it.id == id }
            ?: error("Exame ausente no índice local")
        val encoded = json.encodeToString(ExamExtraction.serializer(), extraction).encodeToByteArray()
        try {
            backend.writeArtifact(id, RESULT, encoded)
        } finally {
            encoded.fill(0)
        }
        val projected = ExamSummaryProjection.project(extraction)
        val updated = existing.copy(
            pageCount = extraction.pages.size,
            totalCount = projected.counts.total,
            reviewCount = projected.counts.reviewCount,
            withinRangeCount = projected.counts.withinRangeCount,
            unclassifiableCount = projected.counts.unclassifiableCount,
            hasPageIssues = projected.counts.hasPageIssues,
            interrupted = false,
        )
        persistIndex(index.copy(entries = index.entries.map { if (it.id == id) updated else it }))
        return updated
    }

    fun delete(id: String) {
        val index = loadAndMigrateIndex()
        val remaining = index.entries.filterNot { it.id == id }
        persistIndex(index.copy(entries = remaining))
        backend.deleteDocument(id)
    }

    fun deleteAll() {
        val index = loadAndMigrateIndex()
        val ids = index.entries.map { it.id }
        persistIndex(ExamHistoryIndex())
        for (id in ids) {
            backend.deleteDocument(id)
        }
    }

    private fun loadAndMigrateIndex(): ExamHistoryIndex {
        var index = readIndexOrEmpty()
        val legacyId = backend.readLegacyCurrentId()
        if (legacyId != null) {
            if (index.entries.none { it.id == legacyId }) {
                val migrated = buildMigratedEntry(legacyId)
                if (migrated != null) {
                    index = index.copy(entries = listOf(migrated) + index.entries)
                    persistIndex(index)
                }
            }
            // Delete legacy pointer only after encrypted index persistence succeeded.
            backend.deleteLegacyCurrentPointer()
        }
        return index
    }

    private fun buildMigratedEntry(id: String): ExamHistoryEntry? {
        if (!backend.existsArtifact(id, ORIGINAL)) return null
        val bytes = try {
            backend.readArtifact(id, RESULT)
        } catch (_: FileNotFoundException) {
            return ExamHistoryEntry(
                id = id,
                importedAtEpochMillis = nowMillis(),
                pageCount = 0,
                totalCount = 0,
                reviewCount = 0,
                withinRangeCount = 0,
                unclassifiableCount = 0,
                hasPageIssues = false,
                interrupted = true,
            )
        }
        val extraction = try {
            json.decodeFromString<ExamExtraction>(bytes.decodeToString())
        } finally {
            bytes.fill(0)
        }
        val projected = ExamSummaryProjection.project(extraction)
        return ExamHistoryEntry(
            id = id,
            importedAtEpochMillis = nowMillis(),
            pageCount = extraction.pages.size,
            totalCount = projected.counts.total,
            reviewCount = projected.counts.reviewCount,
            withinRangeCount = projected.counts.withinRangeCount,
            unclassifiableCount = projected.counts.unclassifiableCount,
            hasPageIssues = projected.counts.hasPageIssues,
            interrupted = false,
        )
    }

    private fun readIndexOrEmpty(): ExamHistoryIndex {
        val raw = backend.readIndex() ?: return ExamHistoryIndex()
        return try {
            json.decodeFromString<ExamHistoryIndex>(raw.decodeToString())
        } finally {
            raw.fill(0)
        }
    }

    private fun persistIndex(index: ExamHistoryIndex) {
        val bytes = json.encodeToString(ExamHistoryIndex.serializer(), index).encodeToByteArray()
        try {
            backend.writeIndex(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        const val ORIGINAL = "original"
        const val RESULT = "ai-extraction-v1"
    }
}

/**
 * Private encrypted multi-exam store backed by Android Keystore AES-GCM.
 * Automatically migrates the legacy single-exam pointer (`current-exam`) without touching workbench files.
 */
class EncryptedExamHistoryStore(context: Context) {
    private val reader = SafDocumentReader(context.contentResolver)
    private val artifacts = EncryptedArtifactStore(context)
    private val legacyPointer = AtomicFile(File(context.noBackupFilesDir, "current-exam"))
    private val controller = ExamHistoryController(AndroidBackend())

    val pdf = PdfPreviewRenderer(context, AuthenticatedPdfSource { id ->
        access { controller.readOriginalPdf(id) }
    })

    suspend fun list(): List<ExamHistoryEntry> = access { controller.list() }

    suspend fun load(id: String): StoredExamRecord? = access { controller.load(id) }

    suspend fun accept(uri: Uri): String = access {
        val source = try {
            reader.readValidated(uri, requiredMime = "application/pdf")
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            val issue = when (failure.message) {
                "DOCUMENT_MIME_MISMATCH", "DOCUMENT_SIGNATURE_INVALID" -> ExamInputIssue.NOT_PDF
                "DOCUMENT_EMPTY" -> ExamInputIssue.EMPTY
                "DOCUMENT_TOO_LARGE" -> ExamInputIssue.TOO_LARGE
                else -> ExamInputIssue.UNREADABLE
            }
            throw ExamInputException(issue)
        }
        try {
            controller.acceptBytes(source.bytes)
        } finally {
            source.bytes.fill(0)
        }
    }

    suspend fun save(id: String, extraction: ExamExtraction): ExamHistoryEntry = access {
        controller.save(id, extraction)
    }

    suspend fun delete(id: String): Unit = access { controller.delete(id) }

    suspend fun cancelActive(id: String): Unit = access { controller.delete(id) }

    suspend fun deleteAll(): Unit = access { controller.deleteAll() }

    private suspend fun <T> access(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { block() }
    }

    private inner class AndroidBackend : HistoryArtifactBackend {
        override fun writeArtifact(documentId: String, artifactId: String, bytes: ByteArray) {
            artifacts.write(documentId, artifactId, ByteArrayInputStream(bytes))
        }

        override fun readArtifact(documentId: String, artifactId: String): ByteArray =
            artifacts.read(documentId, artifactId)

        override fun existsArtifact(documentId: String, artifactId: String): Boolean =
            artifacts.exists(documentId, artifactId)

        override fun deleteDocument(documentId: String) {
            artifacts.deleteDocument(documentId)
        }

        override fun readIndex(): ByteArray? = try {
            artifacts.read(EncryptedArtifactStore.HISTORY_INDEX_DOC, EncryptedArtifactStore.HISTORY_INDEX_ARTIFACT)
        } catch (_: FileNotFoundException) {
            null
        }

        override fun writeIndex(bytes: ByteArray) {
            artifacts.overwriteHistoryIndex(ByteArrayInputStream(bytes))
        }

        override fun readLegacyCurrentId(): String? = try {
            legacyPointer.openRead().use { input ->
                val bytes = ByteArray(36)
                java.io.DataInputStream(input).readFully(bytes)
                val id = bytes.decodeToString()
                check(input.read() == -1 && UUID.fromString(id).toString() == id)
                id
            }
        } catch (_: FileNotFoundException) {
            null
        }

        override fun deleteLegacyCurrentPointer() {
            legacyPointer.delete()
        }
    }

    companion object {
        private val lock = Mutex()
    }
}
