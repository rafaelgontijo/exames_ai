package br.com.exames.datalocal

import br.com.exames.domain.usecase.RunWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outbox acknowledgement follows cancellation and a durable, locked file-write barrier. */
class ProtectedDeletionCleanup(private val factory: ProtectedDatabaseFactory,
    private val artifacts: EncryptedArtifactStore, private val scheduler: RunWorkScheduler,
    private val afterFilesDeleted: () -> Unit = {}) {
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        val dao = factory.open().records()
        // Also handles old v2 outboxes whose run IDs were already cascaded before this upgrade.
        for (id in scheduler.activeRunIds()) if (dao.run(id) == null) scheduler.cancel(id)
        var cleaned = 0
        for (entry in dao.pendingDeletions()) {
            for (id in dao.deletionRunIds(entry.documentId)) scheduler.cancel(id)
            artifacts.deleteDocument(entry.documentId)
            afterFilesDeleted()
            dao.acknowledgeDeletion(entry.documentId)
            cleaned++
        }
        cleaned
    }
}
