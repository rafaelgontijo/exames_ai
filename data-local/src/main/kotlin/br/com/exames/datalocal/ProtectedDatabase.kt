package br.com.exames.datalocal

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "document_tombstones")
data class DocumentTombstone(@PrimaryKey val documentId: String)

@Entity(tableName = "deletion_work", foreignKeys = [ForeignKey(entity = PendingDeletionEntity::class,
    parentColumns = ["documentId"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)], indices = [Index("documentId")])
data class DeletionWork(@PrimaryKey val runId: String, val documentId: String)

/** Only opaque identifiers and operational metadata are searchable; sensitive data is ciphertext. */
@Entity(tableName = "documents")
data class ProtectedDocumentRow(@PrimaryKey val id: String, val importedAt: Long, val payload: ByteArray)

@Entity(tableName = "processing_runs", foreignKeys = [ForeignKey(entity = ProtectedDocumentRow::class,
    parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId"), Index(value = ["id", "documentId"], unique = true)])
data class ProtectedRunRow(@PrimaryKey val id: String, val documentId: String, val state: String,
                           val startedAt: Long, val payload: ByteArray)

@Entity(tableName = "observations", foreignKeys = [ForeignKey(entity = ProtectedRunRow::class,
    parentColumns = ["id", "documentId"], childColumns = ["runId", "documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["runId", "documentId"]), Index(value = ["id", "runId"], unique = true)])
data class ProtectedObservationRow(@PrimaryKey val id: String, val documentId: String, val runId: String,
                                   val page: Int, val ordinal: Int, val payload: ByteArray)

@Entity(tableName = "reference_ranges", foreignKeys = [ForeignKey(entity = ProtectedObservationRow::class,
    parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.CASCADE)])
data class ProtectedReferenceRow(@PrimaryKey val observationId: String, val payload: ByteArray)

@Entity(tableName = "assessments", foreignKeys = [ForeignKey(entity = ProtectedObservationRow::class,
    parentColumns = ["id", "runId"], childColumns = ["observationId", "runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["observationId", "runId"])])
data class ProtectedAssessmentRow(@PrimaryKey val id: String, val observationId: String, val runId: String,
                                  val payload: ByteArray)

@Entity(tableName = "manual_corrections", foreignKeys = [ForeignKey(entity = ProtectedObservationRow::class,
    parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("observationId")])
data class ProtectedCorrectionRow(@PrimaryKey val id: String, val observationId: String, val payload: ByteArray)

@Entity(tableName = "ground_truth_versions", foreignKeys = [ForeignKey(entity = ProtectedDocumentRow::class,
    parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["documentId", "version"], unique = true), Index(value = ["id", "documentId"], unique = true)])
data class ProtectedGroundTruthRow(@PrimaryKey val id: String, val documentId: String, val version: String,
                                   val payload: ByteArray)

@Entity(tableName = "comparisons", foreignKeys = [
    ForeignKey(entity = ProtectedRunRow::class, parentColumns = ["id", "documentId"],
        childColumns = ["runId", "documentId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ProtectedGroundTruthRow::class, parentColumns = ["id", "documentId"],
        childColumns = ["groundTruthId", "documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["runId", "documentId"]), Index(value = ["groundTruthId", "documentId"])])
data class ProtectedComparisonRow(@PrimaryKey val id: String, val documentId: String, val runId: String,
                                  val groundTruthId: String, val payload: ByteArray)

@Entity(tableName = "page_checkpoints", primaryKeys = ["runId", "page"], foreignKeys = [
    ForeignKey(entity = ProtectedRunRow::class, parentColumns = ["id"], childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE)])
data class ProtectedPageRow(val runId: String, val page: Int, val state: String, val payload: ByteArray)

@Dao
abstract class ProtectedDao {
    @Query("SELECT * FROM documents ORDER BY importedAt DESC, id")
    abstract fun observeDocuments(): Flow<List<ProtectedDocumentRow>>
    @Query("SELECT * FROM documents WHERE id = :id")
    abstract suspend fun document(id: String): ProtectedDocumentRow?
    @Update abstract suspend fun updateDocument(row: ProtectedDocumentRow)
    @Query("SELECT * FROM documents ORDER BY importedAt, id")
    abstract suspend fun allDocuments(): List<ProtectedDocumentRow>
    @Query("SELECT * FROM processing_runs WHERE documentId = :documentId ORDER BY startedAt, id")
    abstract suspend fun runs(documentId: String): List<ProtectedRunRow>
    @Query("SELECT * FROM processing_runs WHERE documentId = :documentId ORDER BY rowid")
    abstract fun observeRuns(documentId: String): Flow<List<ProtectedRunRow>>
    @Query("UPDATE processing_runs SET state = :state, payload = :payload WHERE id = :id")
    abstract suspend fun updateRunState(id: String, state: String, payload: ByteArray)
    @Query("SELECT * FROM processing_runs WHERE id = :id")
    abstract suspend fun run(id: String): ProtectedRunRow?
    @Query("SELECT * FROM processing_runs WHERE state IN ('QUEUED', 'RUNNING') ORDER BY startedAt, id")
    abstract suspend fun recoverableRuns(): List<ProtectedRunRow>
    @Query("SELECT * FROM observations WHERE runId = :runId ORDER BY page, ordinal, id")
    abstract suspend fun observations(runId: String): List<ProtectedObservationRow>
    @Query("SELECT * FROM observations WHERE id = :id")
    abstract suspend fun observation(id: String): ProtectedObservationRow?
    @Query("SELECT * FROM reference_ranges WHERE observationId = :id")
    abstract suspend fun reference(id: String): ProtectedReferenceRow?
    @Query("SELECT * FROM assessments WHERE runId = :runId ORDER BY id")
    abstract suspend fun assessments(runId: String): List<ProtectedAssessmentRow>
    @Query("SELECT * FROM manual_corrections WHERE observationId = :id ORDER BY rowid")
    abstract suspend fun corrections(id: String): List<ProtectedCorrectionRow>
    @Query("SELECT * FROM ground_truth_versions WHERE documentId = :id ORDER BY rowid")
    abstract suspend fun groundTruth(id: String): List<ProtectedGroundTruthRow>
    @Query("SELECT * FROM comparisons WHERE runId = :id ORDER BY id")
    abstract suspend fun comparisons(id: String): List<ProtectedComparisonRow>
    @Query("SELECT * FROM page_checkpoints WHERE runId = :id ORDER BY page")
    abstract suspend fun checkpoints(id: String): List<ProtectedPageRow>
    @Query("SELECT * FROM page_checkpoints WHERE runId = :id ORDER BY page")
    abstract fun observeCheckpoints(id: String): Flow<List<ProtectedPageRow>>
    @Query("SELECT * FROM pending_deletions ORDER BY documentId")
    abstract suspend fun pendingDeletions(): List<PendingDeletionEntity>
    @Query("SELECT * FROM document_tombstones WHERE documentId = :id")
    abstract suspend fun tombstone(id: String): DocumentTombstone?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTombstone(row: DocumentTombstone)
    @Query("INSERT OR IGNORE INTO deletion_work (runId,documentId) SELECT id,documentId FROM processing_runs WHERE documentId = :id")
    protected abstract suspend fun captureDeletionWork(id: String)
    @Query("SELECT runId FROM deletion_work WHERE documentId = :id ORDER BY runId")
    abstract suspend fun deletionRunIds(id: String): List<String>

    // ABORT is intentional: audit outputs are never silently replaced.
    @Insert abstract suspend fun insertDocument(row: ProtectedDocumentRow)
    @Insert abstract suspend fun insertRun(row: ProtectedRunRow)
    @Insert abstract suspend fun insertObservations(rows: List<ProtectedObservationRow>)
    @Insert abstract suspend fun insertReference(row: ProtectedReferenceRow)
    @Insert abstract suspend fun insertAssessment(row: ProtectedAssessmentRow)
    @Insert abstract suspend fun appendCorrection(row: ProtectedCorrectionRow)
    @Insert abstract suspend fun appendGroundTruth(row: ProtectedGroundTruthRow)
    @Insert abstract suspend fun appendComparison(row: ProtectedComparisonRow)
    @Upsert abstract suspend fun checkpoint(row: ProtectedPageRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun enqueueDeletion(row: PendingDeletionEntity)
    @Query("DELETE FROM documents WHERE id = :id")
    protected abstract suspend fun deleteDocumentRows(id: String)
    @Query("DELETE FROM pending_deletions WHERE documentId = :id")
    abstract suspend fun acknowledgeDeletion(id: String)

    @Transaction
    open suspend fun deleteCompletely(id: String) {
        if (tombstone(id) != null) return
        insertTombstone(DocumentTombstone(id))
        enqueueDeletion(PendingDeletionEntity(id))
        captureDeletionWork(id)
        deleteDocumentRows(id)
    }
}

/** Separate until the verified v1 destination migration and shared factory are integrated. */
@Database(entities = [ProtectedDocumentRow::class, ProtectedRunRow::class, ProtectedObservationRow::class,
    ProtectedReferenceRow::class, ProtectedAssessmentRow::class, ProtectedCorrectionRow::class,
    ProtectedGroundTruthRow::class, ProtectedComparisonRow::class, ProtectedPageRow::class,
    PendingDeletionEntity::class, DocumentTombstone::class, DeletionWork::class], version = 3, exportSchema = true)
abstract class ProtectedDatabase : RoomDatabase() {
    abstract fun records(): ProtectedDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS document_tombstones (documentId TEXT NOT NULL, PRIMARY KEY(documentId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS deletion_work (runId TEXT NOT NULL, documentId TEXT NOT NULL, PRIMARY KEY(runId), FOREIGN KEY(documentId) REFERENCES pending_deletions(documentId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deletion_work_documentId ON deletion_work(documentId)")
                db.execSQL("INSERT OR IGNORE INTO document_tombstones(documentId) SELECT documentId FROM pending_deletions")
            }
        }
    }
}
