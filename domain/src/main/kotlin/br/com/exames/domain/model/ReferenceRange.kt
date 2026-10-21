package br.com.exames.domain.model

import java.math.BigDecimal

data class ReferenceRange(
    val observationId: String,
    val referenceRaw: String,
    val unit: String?,
    val sourcePage: Int,
    val low: BigDecimal? = null,
    val high: BigDecimal? = null,
    val comparator: ReferenceComparator = ReferenceComparator.RANGE,
    val lowInclusive: Boolean = true,
    val highInclusive: Boolean = true,
    val applicabilityNotes: String? = null,
    val applicability: Applicability = Applicability.UNKNOWN,
)
enum class ReferenceComparator { RANGE, LT, LTE, GT, GTE, UNKNOWN }
enum class Applicability { APPLICABLE, UNKNOWN, NOT_APPLICABLE }
