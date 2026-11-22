package br.com.exames.datalocal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ResultShareDependencies { fun resultShareStore(): ResultShareStore }

/** Android verifies URI grants before dispatch to this non-exported provider. */
class ResultShareProvider : ContentProvider() {
    private val store by lazy { EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext,
        ResultShareDependencies::class.java).resultShareStore() }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/plain"

    override fun openFile(uri: Uri, mode: String) = access {
        if (mode != "r") throw FileNotFoundException()
        store.open(uri)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?) = access {
        val size = store.size(uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        MatrixCursor(columns.toTypedArray(), 1).apply {
            val row = newRow()
            for (col in columns) row.add(if (col == OpenableColumns.SIZE) size else "resumo.txt")
        }
    }

    private fun <T> access(block: suspend () -> T): T = try { runBlocking { block() } }
        catch (_: Exception) { throw FileNotFoundException("Resumo indisponível") }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
