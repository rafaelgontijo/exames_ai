package br.com.exames.datalocal

import br.com.exames.domain.model.*
import kotlinx.serialization.json.*
import java.time.Instant

/** Field names intentionally match v1 payload copies, including flattened bounding boxes. */
internal object ModelPayloads {
    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (key, value) -> key to when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    } })
    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.n(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.time(key: String) = Instant.ofEpochMilli(getValue(key).jsonPrimitive.long)

    fun document(value: Document) = obj("id" to value.id, "uriLocal" to value.uriLocal, "hash" to value.hash,
        "mimeType" to value.mimeType, "importedAt" to value.importedAt.toEpochMilli(), "pageCount" to value.pageCount,
        "processingState" to value.processingState.name)
    fun document(value: JsonObject) = Document(value.s("id"), value.s("uriLocal"), value.s("hash"), value.s("mimeType"),
        value.time("importedAt"), value.n("pageCount")?.toInt(), ProcessingState.valueOf(value.s("processingState")))

    fun observation(value: Observation) = obj("id" to value.id, "documentId" to value.documentId,
        "analyteRaw" to value.analyteRaw, "analyteNormalized" to value.analyteNormalized, "resultRaw" to value.resultRaw,
        "numericValue" to value.numericValue?.toPlainString(), "unitRaw" to value.unitRaw,
        "unitNormalized" to value.unitNormalized, "page" to value.page, "sourceSnippet" to value.sourceSnippet,
        "box_left" to value.boundingBox?.left, "box_top" to value.boundingBox?.top,
        "box_right" to value.boundingBox?.right, "box_bottom" to value.boundingBox?.bottom,
        "extractionConfidence" to value.extractionConfidence, "resultComparator" to value.resultComparator.name,
        "ambiguities" to value.ambiguities.sortedBy { it.name }.joinToString(",") { it.name }, "confirmed" to value.confirmed)
    fun observation(value: JsonObject): Observation {
        val left = value.n("box_left")?.toInt()
        val box = left?.let { BoundingBox(it, value.s("box_top").toInt(), value.s("box_right").toInt(), value.s("box_bottom").toInt()) }
        val confirmed = value.getValue("confirmed").jsonPrimitive.let { it.booleanOrNull ?: (it.int == 1) }
        return Observation(value.s("id"), value.s("documentId"), value.s("analyteRaw"), value.s("resultRaw"),
            value.n("unitRaw"), value.s("page").toInt(), value.s("sourceSnippet"), box,
            value.n("extractionConfidence")?.toFloat(), value.n("analyteNormalized"),
            value.n("numericValue")?.toBigDecimal(), value.n("unitNormalized"),
            ResultComparator.valueOf(value.s("resultComparator")),
            value.s("ambiguities").takeIf { it.isNotEmpty() }?.split(',')?.map(Ambiguity::valueOf)?.toSet() ?: emptySet(), confirmed)
    }

    fun reference(value: ReferenceRange) = obj("observationId" to value.observationId, "low" to value.low?.toPlainString(),
        "high" to value.high?.toPlainString(), "comparator" to value.comparator.name, "lowInclusive" to value.lowInclusive,
        "highInclusive" to value.highInclusive, "unit" to value.unit, "referenceRaw" to value.referenceRaw,
        "applicabilityNotes" to value.applicabilityNotes, "sourcePage" to value.sourcePage, "applicability" to value.applicability.name)
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.let { it.booleanOrNull ?: (it.int == 1) }
    fun reference(value: JsonObject) = ReferenceRange(value.s("observationId"), value.s("referenceRaw"), value.n("unit"),
        value.s("sourcePage").toInt(), value.n("low")?.toBigDecimal(), value.n("high")?.toBigDecimal(),
        ReferenceComparator.valueOf(value.s("comparator")), value.bool("lowInclusive"), value.bool("highInclusive"),
        value.n("applicabilityNotes"), Applicability.valueOf(value.s("applicability")))

    fun assessment(value: Assessment) = obj("observationId" to value.observationId, "status" to value.status.name,
        "reasonCode" to value.reasonCode.name, "evaluatedAt" to value.evaluatedAt.toEpochMilli(), "ruleVersion" to value.ruleVersion)
    fun assessment(value: JsonObject) = Assessment(value.s("observationId"), AssessmentStatus.valueOf(value.s("status")),
        ReasonCode.valueOf(value.s("reasonCode")), value.time("evaluatedAt"), value.s("ruleVersion"))

    fun correction(value: ManualCorrection) = obj("id" to value.id, "observationId" to value.observationId,
        "field" to value.field.name, "oldValue" to value.oldValue, "newValue" to value.newValue,
        "confirmedAt" to value.confirmedAt.toEpochMilli())
    fun correction(value: JsonObject) = ManualCorrection(value.s("id"), value.s("observationId"),
        CorrectionField.valueOf(value.s("field")), value.n("oldValue"), value.n("newValue"), value.time("confirmedAt"))
}
