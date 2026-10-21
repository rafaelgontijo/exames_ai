package br.com.exames.datalocal

import androidx.room.*
import br.com.exames.domain.model.*
import java.math.BigDecimal
import java.time.Instant

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String, val uriLocal: String, val hash: String,
    val mimeType: String, val importedAt: Instant, val pageCount: Int?, val processingState: ProcessingState,
)
@Entity(tableName = "observations", foreignKeys = [ForeignKey(entity = DocumentEntity::class,
    parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")])
data class ObservationEntity(
    @PrimaryKey val id: String, val documentId: String,
    val analyteRaw: String, val analyteNormalized: String?, val resultRaw: String,
    val numericValue: BigDecimal?, val unitRaw: String?, val unitNormalized: String?,
    val page: Int, val sourceSnippet: String,
    @Embedded(prefix = "box_") val boundingBox: BoundingBox?,
    val extractionConfidence: Float?, val resultComparator: ResultComparator,
    val ambiguities: Set<Ambiguity>, val confirmed: Boolean,
)
@Entity(tableName = "reference_ranges", foreignKeys = [ForeignKey(entity = ObservationEntity::class,
    parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.CASCADE)])
data class ReferenceRangeEntity(
    @PrimaryKey val observationId: String, val low: BigDecimal?, val high: BigDecimal?,
    val comparator: ReferenceComparator, val lowInclusive: Boolean, val highInclusive: Boolean,
    val unit: String?, val referenceRaw: String, val applicabilityNotes: String?,
    val sourcePage: Int, val applicability: Applicability,
)
@Entity(tableName = "assessments", foreignKeys = [ForeignKey(entity = ObservationEntity::class,
    parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.CASCADE)])
data class AssessmentEntity(
    @PrimaryKey val observationId: String, val status: AssessmentStatus,
    val reasonCode: ReasonCode, val evaluatedAt: Instant, val ruleVersion: String,
)
@Entity(tableName = "manual_corrections", foreignKeys = [ForeignKey(entity = ObservationEntity::class,
    parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("observationId")])
data class ManualCorrectionEntity(
    @PrimaryKey val id: String, val observationId: String, val field: CorrectionField,
    val oldValue: String?, val newValue: String?, val confirmedAt: Instant,
)
/** Opaque directory IDs only; outlives document rows for retryable artifact deletion. */
@Entity(tableName = "pending_deletions")
data class PendingDeletionEntity(@PrimaryKey val documentId: String)
