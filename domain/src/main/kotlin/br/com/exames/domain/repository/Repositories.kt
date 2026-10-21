package br.com.exames.domain.repository

import br.com.exames.domain.model.*
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeDocuments(): Flow<List<Document>>
    suspend fun find(id: String): Document?
    suspend fun save(document: Document)
    /** Hide atomically, enqueue all artifacts for deletion, then retry cleanup until complete. */
    suspend fun deleteCompletely(id: String)
}
interface ObservationRepository {
    fun observeByDocument(documentId: String): Flow<List<Observation>>
    suspend fun save(observations: List<Observation>)
    suspend fun appendCorrection(correction: ManualCorrection)
}
interface AssessmentRepository {
    fun observeByDocument(documentId: String): Flow<List<Assessment>>
    suspend fun save(assessment: Assessment)
}
interface DocumentParser {
    /** Must preserve raw text/provenance; unresolved associations require human review. */
    suspend fun parse(documentId: String): List<Observation>
}
