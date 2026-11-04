package br.com.exames.datadocuments

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** No broad storage permission. URI and display name are sensitive; never log them. */
class SafDocumentReader(private val resolver: ContentResolver) {
    /** Provider name/size are never trusted or persisted. No URI grant is retained. */
    suspend fun readValidated(uri: Uri): ValidatedDocument = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "DOCUMENT_URI_INVALID" }
        val context = currentCoroutineContext()
        val mime = resolver.getType(uri)
        val input = checkNotNull(resolver.openInputStream(uri)) { "DOCUMENT_UNAVAILABLE" }
        input.use { ValidatedDocumentInput.read(it, mime) { context.ensureActive() } }
    }
    suspend fun inspect(uri: Uri): SelectedDocument = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val mime = resolver.getType(uri) ?: error("Formato desconhecido")
        require(mime in SUPPORTED) { "Formato não suportado" }
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
        require(size == null || size in 1..MAX_BYTES) { "Tamanho não suportado" }
        SelectedDocument(uri, mime, size)
    }
    companion object {
        val SUPPORTED = setOf("application/pdf", "image/jpeg", "image/png")
        val MAX_BYTES = DocumentLimits.MAX_BYTES.toLong() // Technical limit; measure on target devices.
    }
}
data class SelectedDocument(val uri: Uri, val mimeType: String, val sizeBytes: Long?)
