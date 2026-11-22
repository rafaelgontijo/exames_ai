package br.com.exames.datalocal

import android.content.ContentResolver
import android.net.Uri
import br.com.exames.domain.model.Document
import br.com.exames.domain.validation.CorpusPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Entire package is validated before any member is published. Completed cases survive interruption. */
class ProtectedCorpusImporter(private val resolver: ContentResolver, private val importer: ProtectedDocumentImporter) {
    suspend fun import(uri: Uri, onImported: suspend (Document) -> Unit): Int = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val context=currentCoroutineContext()
        val bytes=requireNotNull(resolver.openInputStream(uri)).use {
            CorpusPackages.readBounded(it, CorpusPackages.MAX_ARCHIVE) { context.ensureActive() }
        }
        try {
            CorpusPackages.decode(bytes) { context.ensureActive() }.use { corpus ->
                for (case in corpus.cases) {
                    context.ensureActive()
                    onImported(importer.importCase(case))
                }
                corpus.cases.size
            }
        } finally { bytes.fill(0) }
    }
}
