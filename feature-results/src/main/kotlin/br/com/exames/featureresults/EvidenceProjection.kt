package br.com.exames.featureresults

import br.com.exames.domain.validation.ExtractedFieldEvidence
import br.com.exames.domain.validation.SourceRegion
import kotlin.math.abs
import kotlin.math.min

enum class EvidenceOrigin { EXTRACTED, GROUND_TRUTH }
data class SelectedEvidence(val origin: EvidenceOrigin, val label: String, val raw: String?,
    val evidence: List<ExtractedFieldEvidence>)
data class RelativeEvidenceBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Regions are already in the upright preview frame, including PDF/EXIF orientation. Never rotate twice. */
object EvidenceProjection {
    fun project(region: SourceRegion, previewWidth: Int, previewHeight: Int): RelativeEvidenceBox? {
        if (previewWidth <= 0 || previewHeight <= 0) return null
        val scale = min(previewWidth / region.pageWidth, previewHeight / region.pageHeight)
        // Allow at most two raster pixels of rounding; do not guess an unrelated/legacy coordinate frame.
        if (abs(region.pageWidth * scale - previewWidth) > 2 || abs(region.pageHeight * scale - previewHeight) > 2) return null
        val box = RelativeEvidenceBox((region.left / region.pageWidth).toFloat(), (region.top / region.pageHeight).toFloat(),
            (region.right / region.pageWidth).toFloat(), (region.bottom / region.pageHeight).toFloat())
        return box.takeIf { it.right > it.left && it.bottom > it.top }
    }
}

internal fun extractedFieldLabel(field: String) = when (field) {
    "analyteRaw" -> "Analito extraído"; "resultRaw" -> "Resultado extraído"; "unitRaw" -> "Unidade extraída"
    "referenceRaw" -> "Referência extraída"; "context" -> "Contexto extraído"; else -> "Campo extraído"
}

internal fun fieldLabel(field: String) = when (field) {
    "analyteRaw", "analyte" -> "analito"; "resultRaw", "numericValue" -> "resultado"; "unitRaw", "unit" -> "unidade"
    "referenceRaw", "low", "high", "referenceAssociation" -> "referência"; "context" -> "contexto"
    "assessmentStatus", "isolated.status" -> "estado da avaliação"; "isolated.reason" -> "motivo da avaliação"; else -> "campo"
}
