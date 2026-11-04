package br.com.exames.domain.validation

import br.com.exames.domain.model.*
import br.com.exames.domain.parser.BiochemistryParser
import java.math.BigDecimal

data class ObservationPair(val expectedId: String, val actualId: String)
data class PairingAmbiguity(val expectedId: String, val candidateActualIds: List<String>)
data class ObservationMatches(val pairs: List<ObservationPair>, val missing: List<String>, val extra: List<String>,
    val unresolved: List<PairingAmbiguity>)
data class ComparedField(val expectedId: String, val actualId: String, val field: String,
    val expected: String?, val actual: String?, val matches: Boolean)
data class ExtractionComparison(val matching: ObservationMatches, val raw: List<ComparedField>, val canonical: List<ComparedField>)

/** One case/document at a time. Pairing deliberately cannot read numeric results or reference values. */
class ExtractionComparator {
    fun match(expected: List<ExpectedObservation>, actual: List<Observation>): ObservationMatches {
        require(expected.map { it.observationId }.distinct().size == expected.size)
        require(actual.map { it.id }.distinct().size == actual.size && actual.map { it.documentId }.distinct().size <= 1)
        val edges = expected.associate { truth -> truth.observationId to actual.filter { observed ->
            val truthAnchors = truth.evidence.filter { it.field == "analyteRaw" }.map { it.region }
            val observedAnchors = observed.fieldEvidence.filter { it.field == "analyteRaw" }.map { it.region }
            val pages = if (truthAnchors.isNotEmpty()) truthAnchors.map { it.page }.toSet()
                else truth.evidence.map { it.region.page }.toSet().takeIf { it.size == 1 }.orEmpty()
            val positioned = truthAnchors.isNotEmpty() && observedAnchors.isNotEmpty()
            BiochemistryParser.resolve(observed.analyteRaw) == truth.analyte && observed.page in pages &&
                observedAnchors.all { it.page == observed.page } &&
                (!positioned || truthAnchors.any { a -> observedAnchors.any { b -> overlap(a, b) } })
        }.map { it.id }.toMutableSet() }.toMutableMap()
        val pairs = mutableListOf<ObservationPair>()
        while (true) {
            val unique = edges.entries.filter { (_, candidates) -> candidates.size == 1 }.filter { (_, candidates) ->
                val id = candidates.single(); edges.values.count { id in it } == 1
            }.map { ObservationPair(it.key, it.value.single()) }
            if (unique.isEmpty()) break
            pairs += unique
            val used = unique.map { it.actualId }.toSet()
            unique.forEach { edges.remove(it.expectedId) }; edges.values.forEach { it.removeAll(used) }
        }
        val foundExpected = pairs.map { it.expectedId }.toSet(); val foundActual = pairs.map { it.actualId }.toSet()
        return ObservationMatches(pairs.sortedBy { it.expectedId },
            expected.map { it.observationId }.filter { it !in foundExpected },
            actual.map { it.id }.filter { it !in foundActual },
            edges.filterValues { it.isNotEmpty() }.map { PairingAmbiguity(it.key, it.value.sorted()) })
    }

    fun compare(expected: List<ExpectedObservation>, actual: List<Observation>,
        references: Map<String, ReferenceRange> = emptyMap(), assessments: Map<String, Assessment> = emptyMap()): ExtractionComparison {
        require(references.keys.all { id -> actual.any { it.id == id } })
        require(assessments.keys.all { id -> actual.any { it.id == id } })
        require(assessments.all { (id, value) -> id == value.observationId }) { "INVALID_ASSESSMENT_IDENTITY" }
        val matching = match(expected, actual)
        val byExpected = expected.associateBy { it.observationId }; val byActual = actual.associateBy { it.id }
        val raw = mutableListOf<ComparedField>(); val canonical = mutableListOf<ComparedField>()
        for (pair in matching.pairs) {
            val truth = byExpected.getValue(pair.expectedId); val observed = byActual.getValue(pair.actualId)
            val reference = references[observed.id]
            fun field(destination: MutableList<ComparedField>, name: String, expectedValue: String?, actualValue: String?) {
                destination += ComparedField(truth.observationId, observed.id, name, expectedValue, actualValue, expectedValue == actualValue)
            }
            field(raw, "analyteRaw", truth.analyteRaw, observed.analyteRaw)
            field(raw, "resultRaw", truth.resultRaw, observed.resultRaw)
            field(raw, "unitRaw", truth.unitRaw, observed.unitRaw)
            field(raw, "referenceRaw", truth.referenceRaw, observed.referenceRaw ?: reference?.referenceRaw)
            field(canonical, "analyte", truth.analyte.name, BiochemistryParser.resolve(observed.analyteRaw)?.name)
            field(canonical, "numericValue", decimal(truth.numericValue), decimal(observed.numericValue?.toPlainString()))
            field(canonical, "unit", truth.unitRaw?.trim(), observed.unitRaw?.trim())
            field(canonical, "low", decimal(truth.low), decimal(reference?.low?.toPlainString()))
            field(canonical, "high", decimal(truth.high), decimal(reference?.high?.toPlainString()))
            val uniqueReference = truth.low != null || truth.high != null
            field(canonical, "referenceAssociation", observed.id.takeIf { uniqueReference }, reference?.observationId)
            field(canonical, "assessmentStatus", truth.expectedStatus.name, assessments[observed.id]?.status?.name)
        }
        return ExtractionComparison(matching, raw, canonical)
    }

    private fun decimal(value: String?): String? = value?.let {
        require(it.matches(Regex("[+-]?[0-9]+(?:\\.[0-9]+)?"))) { "INVALID_CANONICAL_DECIMAL" }
        BigDecimal(it).stripTrailingZeros().toPlainString()
    }
    private fun overlap(a: SourceRegion, b: SourceRegion): Boolean = a.page == b.page &&
        minOf(a.right / a.pageWidth, b.right / b.pageWidth) > maxOf(a.left / a.pageWidth, b.left / b.pageWidth) &&
        minOf(a.bottom / a.pageHeight, b.bottom / b.pageHeight) > maxOf(a.top / a.pageHeight, b.top / b.pageHeight)

    companion object { const val VERSION = "case-page-analyte-evidence/1" }
}
