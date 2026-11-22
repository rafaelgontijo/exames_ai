package br.com.exames.datalocal

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import br.com.exames.datadocuments.AuthenticatedPdfSource
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.datadocuments.SafDocumentReader
import br.com.exames.domain.extraction.ExamExtraction
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

enum class ExamInputIssue { NOT_PDF, EMPTY, TOO_LARGE, UNREADABLE }
class ExamInputException(val issue: ExamInputIssue) : IOException(issue.name)

/** Only the opaque ID appears outside authenticated encryption. An absent result means interrupted. */
class CurrentExamStore(context: Context) {
    private val reader = SafDocumentReader(context.contentResolver)
    private val artifacts = EncryptedArtifactStore(context)
    private val pointer = AtomicFile(File(context.noBackupFilesDir, "current-exam"))
    val pdf = PdfPreviewRenderer(context, AuthenticatedPdfSource { id ->
        access {
            check(currentId() == id)
            artifacts.read(id, ORIGINAL)
        }
    })

    class Current(val id: String, val extraction: ExamExtraction?)

    suspend fun current(): Current? = access {
        val id = currentId() ?: return@access null
        val bytes = try {
            artifacts.read(id, RESULT)
        } catch (_: FileNotFoundException) {
            return@access Current(id, null)
        }
        try {
            Current(id, Json.decodeFromString<ExamExtraction>(bytes.decodeToString()))
        } finally {
            bytes.fill(0)
        }
    }

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
            removeCurrent()
            val id = UUID.randomUUID().toString()
            val stream = pointer.startWrite()
            try {
                stream.write(id.encodeToByteArray())
                pointer.finishWrite(stream)
            } catch (failure: Exception) {
                pointer.failWrite(stream)
                throw failure
            }
            // Fail closed if AtomicFile could not replace the pointer. No untracked source is written.
            check(currentId() == id)
            artifacts.write(id, ORIGINAL, source.bytes.inputStream())
            id
        } finally {
            source.bytes.fill(0)
        }
    }

    suspend fun save(id: String, extraction: ExamExtraction) = access {
        check(currentId() == id)
        val bytes = Json.encodeToString(ExamExtraction.serializer(), extraction).encodeToByteArray()
        try {
            artifacts.write(id, RESULT, bytes.inputStream())
        } finally {
            bytes.fill(0)
        }
        Unit
    }

    suspend fun discard(): Unit = access { removeCurrent() }

    private fun removeCurrent() {
        val id = currentId() ?: return
        artifacts.deleteDocument(id)
        pointer.delete()
        check(currentId() == null)
    }

    private fun currentId(): String? = try {
        pointer.openRead().use { input ->
            val bytes = ByteArray(36)
            java.io.DataInputStream(input).readFully(bytes)
            val id = bytes.decodeToString()
            check(input.read() == -1 && UUID.fromString(id).toString() == id)
            id
        }
    } catch (_: FileNotFoundException) {
        null
    }

    private suspend fun <T> access(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    companion object {
        private val lock = Mutex()
        private const val ORIGINAL = "original"
        private const val RESULT = "ai-extraction-v1"
    }
}
