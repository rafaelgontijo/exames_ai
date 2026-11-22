package br.com.exames.datalocal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import br.com.exames.domain.summary.SummaryText
import br.com.exames.domain.summary.SummaryTextResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local capabilities. Only this process owns the provider and the live writers.
 * Index and text are immutable encrypted artifacts, both covered by the document outbox.
 */
@Singleton
class ResultShareStore @Inject constructor(@param:ApplicationContext private val context: Context,
    private val factory: ProtectedDatabaseFactory, private val artifacts: EncryptedArtifactStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    internal var elapsedMillis: () -> Long = SystemClock::elapsedRealtime
        private set

    internal constructor(context: Context, factory: ProtectedDatabaseFactory, artifacts: EncryptedArtifactStore,
        clock: () -> Long) : this(context, factory, artifacts) { elapsedMillis = clock }

    private val session = UUID.randomUUID().toString()
    private val active = mutableMapOf<String, Export>()
    private val authority = context.packageName + ".resultshare"
    private var reconciled = false

    private data class Export(val document: String, val run: String, val token: String, val expires: Long,
        val size: Int, val digest: String, val writers: MutableSet<ParcelFileDescriptor> = mutableSetOf())

    private fun uri(token: String) = Uri.Builder().scheme("content").authority(authority).appendPath(token).build()
    private fun token(uri: Uri): String {
        val id = uri.lastPathSegment ?: denied()
        if (!TOKEN.matches(id) || uri != uri(id)) denied()
        return id
    }
    private fun denied(): Nothing = throw FileNotFoundException("Resumo indisponível")
    private fun index(entry: Export) = listOf("1", session, entry.document, entry.run, entry.token,
        entry.expires, entry.size, entry.digest).joinToString("\n").toByteArray()
    private fun digest(bytes: ByteArray) = android.util.Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(bytes), android.util.Base64.NO_WRAP)

    private suspend fun ownerExists(doc: String, run: String): Boolean {
        val dao = factory.open().records()
        return dao.run(run)?.documentId == doc && dao.tombstone(doc) == null && dao.document(doc) != null
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) { mutex.withLock { reconcileLocked() } }

    private suspend fun reconcileLocked() {
        val dao = factory.open().records()
        for (entry in active.values.toList()) {
            if (dao.tombstone(entry.document) != null) invalidate(entry, removeFiles = false)
            else if (elapsedMillis() >= entry.expires) invalidate(entry)
        }
        for ((doc, artifact) in artifacts.exportArtifacts()) {
            val id = artifact.substringAfterLast('-').substringBefore('.')
            if (!artifact.endsWith(".pending") && active[id]?.document == doc) continue
            revokeGrant(id)
            // The durable outbox owns the entire directory after deletion.
            if (dao.tombstone(doc) == null) artifacts.removeExport(doc, artifact)
        }
        reconciled = true
    }

    suspend fun prepare(document: String, run: String, summary: SummaryTextResult.Rendered): Uri =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (!reconciled) reconcileLocked()
            if (!ownerExists(document, run) || summary.text.length > SummaryText.LIMIT_BYTES) denied()
            val bytes = summary.text.toByteArray()
            try {
                if (bytes.size !in 1..SummaryText.LIMIT_BYTES || bytes.size != summary.utf8Bytes) denied()
                val id = UUID.randomUUID().toString().replace("-", "")
                val entry = Export(document, run, id, elapsedMillis() + TTL, bytes.size, digest(bytes))
                try {
                    artifacts.write(document, "share-index-$id", index(entry).inputStream())
                    artifacts.write(document, "share-text-$id", bytes.inputStream())
                } catch (error: Exception) {
                    reconciled = false
                    throw error
                }
                active[id] = entry
                scope.launch {
                    delay(TTL)
                    mutex.withLock { if (active[id] === entry) invalidate(entry) }
                }
                uri(id)
            } finally { bytes.fill(0) }
        } }

    private suspend fun resolve(uri: Uri): Export {
        val entry = active[token(uri)] ?: denied()
        if (elapsedMillis() >= entry.expires || !ownerExists(entry.document, entry.run)) {
            invalidate(entry)
            denied()
        }
        return entry
    }

    suspend fun size(uri: Uri) = withContext(Dispatchers.IO) { mutex.withLock { resolve(uri).size } }

    suspend fun open(uri: Uri): ParcelFileDescriptor = withContext(Dispatchers.IO) { mutex.withLock {
        val entry = resolve(uri)
        val diskIndex = artifacts.read(entry.document, "share-index-" + entry.token)
        try { if (!diskIndex.contentEquals(index(entry))) denied() } finally { diskIndex.fill(0) }
        val bytes = artifacts.read(entry.document, "share-text-" + entry.token)
        try {
            if (bytes.size != entry.size || digest(bytes) != entry.digest) denied()
            val (reader, writer) = ParcelFileDescriptor.createPipe()
            try {
                val fd = writer.fileDescriptor
                Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                entry.writers += writer
                scope.launch { stream(entry, writer, bytes) }
                reader
            } catch (error: Exception) { reader.close(); writer.close(); throw error }
        } catch (error: Exception) { bytes.fill(0); throw error }
    } }

    /** A nonblocking write inside the barrier lets deletion/expiry close even a stalled recipient. */
    private suspend fun stream(entry: Export, writer: ParcelFileDescriptor, bytes: ByteArray) {
        try {
            var offset = 0
            while (offset < bytes.size) {
                val n = mutex.withLock {
                    if (active[entry.token] !== entry || elapsedMillis() >= entry.expires) return
                    try { Os.write(writer.fileDescriptor, bytes, offset, minOf(4096, bytes.size - offset)) }
                    catch (e: ErrnoException) { if (e.errno == OsConstants.EAGAIN) 0 else throw e }
                }
                if (n == 0) delay(10) else offset += n
            }
        } catch (_: Exception) {
            // Recipient closed its pipe. It may have received a prefix.
        } finally {
            bytes.fill(0)
            mutex.withLock { entry.writers.remove(writer); writer.close() }
        }
    }

    private fun revokeGrant(id: String) =
        context.revokeUriPermission(uri(id), Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun invalidate(entry: Export, removeFiles: Boolean = true) {
        active.remove(entry.token)
        entry.writers.forEach { runCatching { it.close() } }
        entry.writers.clear()
        revokeGrant(entry.token)
        if (removeFiles) {
            try {
                artifacts.removeExport(entry.document, "share-text-" + entry.token)
                artifacts.removeExport(entry.document, "share-index-" + entry.token)
            } catch (_: Exception) { reconciled = false } // Reconciliation or deletion outbox retries.
        }
    }

    suspend fun revoke(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock { active[token(uri)]?.let { invalidate(it) }; Unit }
    }

    /** Hold the same barrier as prepare/open/write across the durable tombstone transaction. */
    suspend fun deleting(document: String, commit: suspend () -> Unit) = mutex.withLock {
        commit()
        withContext(NonCancellable) {
            active.values.filter { it.document == document }.forEach { invalidate(it, removeFiles = false) }
        }
    }

    companion object {
        internal const val TTL = 600_000L
        private val TOKEN = Regex("[0-9a-f]{32}")
    }
}
