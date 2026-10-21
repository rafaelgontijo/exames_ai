package br.com.exames.domain.model

import java.time.Instant

/** Append-only audit; original Observation raw fields must never be overwritten. */
data class ManualCorrection(
    val id: String,
    val observationId: String,
    val field: CorrectionField,
    val oldValue: String?,
    val newValue: String?,
    val confirmedAt: Instant,
)
enum class CorrectionField { ANALYTE, RESULT, UNIT, REFERENCE, APPLICABILITY }
