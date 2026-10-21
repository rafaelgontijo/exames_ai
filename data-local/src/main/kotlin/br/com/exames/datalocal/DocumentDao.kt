package br.com.exames.datalocal

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DocumentDao {
    @Query("SELECT * FROM documents ORDER BY importedAt DESC")
    abstract fun observeAll(): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE id = :id")
    abstract suspend fun find(id: String): DocumentEntity?
    @Upsert abstract suspend fun upsert(document: DocumentEntity)
    @Query("DELETE FROM documents WHERE id = :id")
    protected abstract suspend fun deleteRows(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun enqueue(entry: PendingDeletionEntity)
    @Query("SELECT * FROM pending_deletions")
    abstract suspend fun pendingDeletions(): List<PendingDeletionEntity>
    @Query("DELETE FROM pending_deletions WHERE documentId = :id")
    abstract suspend fun acknowledgeDeletion(id: String)
    /** Atomic visibility + cascading rows + durable file cleanup intent. */
    @Transaction open suspend fun enqueueCompleteDeletion(id: String) {
        enqueue(PendingDeletionEntity(id))
        deleteRows(id)
    }
}
