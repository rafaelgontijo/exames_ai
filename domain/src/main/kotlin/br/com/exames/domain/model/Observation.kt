package br.com.exames.domain.model

import java.math.BigDecimal

data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class Observation(
    val id: String,
    val documentId: String,
    val analyteRaw: String,
    val resultRaw: String,
    val unitRaw: String?,
    val page: Int,
    val sourceSnippet: String,
    val boundingBox: BoundingBox? = null,
    val extractionConfidence: Float? = null,
    val analyteNormalized: String? = null,
    val numericValue: BigDecimal? = null,
    val unitNormalized: String? = null,
    val resultComparator: ResultComparator = ResultComparator.EQ,
    val ambiguities: Set<Ambiguity> = emptySet(),
    /** Human review metadata only; never set automatically to enable classification. */
    val confirmed: Boolean = false,
)
enum class ResultComparator { EQ, LT, LTE, GT, GTE, UNKNOWN }
enum class Ambiguity { VALUE, UNIT, COMPARATOR, CONTEXT, REFERENCE_ASSOCIATION }
