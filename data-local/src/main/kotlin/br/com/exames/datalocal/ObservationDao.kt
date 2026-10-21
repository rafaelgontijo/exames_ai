package br.com.exames.datalocal

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservationDao {
    @Query("SELECT * FROM observations WHERE documentId = :documentId ORDER BY page, id")
    fun observeByDocument(documentId: String): Flow<List<ObservationEntity>>
    @Upsert suspend fun upsert(observations: List<ObservationEntity>)
    @Upsert suspend fun upsertReference(reference: ReferenceRangeEntity)
    @Upsert suspend fun upsertAssessment(assessment: AssessmentEntity)
    // ABORT prevents silent replacement of an audit record.
    @Insert suspend fun appendCorrection(correction: ManualCorrectionEntity)
}
