package br.com.exames.featureresults

import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.validation.*

data class DifferenceSource(val observationId: String, val field: String, val pages: List<Int>)
data class DifferenceEvidence(val original: DifferenceSource?, val expected: DifferenceSource?, val truth: GroundTruthSnapshot?)

/** Link by the versioned analyte/page/evidence matcher, never by the expected clinical value. */
internal fun differenceEvidence(comparison: Comparison, difference: FieldDifference, run: ProcessingRun,
    rows: List<ResultRow>, snapshots: List<GroundTruthSnapshot>): DifferenceEvidence {
    if (comparison.runId != run.runId) return DifferenceEvidence(null, null, null)
    val field = when (difference.field) {
        "analyteRaw", "raw.analyteRaw", "canonical.analyte" -> "analyteRaw"
        "resultRaw", "raw.resultRaw", "canonical.numericValue" -> "resultRaw"
        "unitRaw", "raw.unitRaw", "canonical.unit" -> "unitRaw"
        "referenceRaw", "raw.referenceRaw", "canonical.low", "canonical.high", "canonical.referenceAssociation" -> "referenceRaw"
        else -> return DifferenceEvidence(null, null, null)
    }
    val snapshot = snapshots.singleOrNull { it.sha256 == comparison.groundTruthSha256 &&
        it.groundTruth.version == comparison.groundTruthVersion && it.groundTruth.caseId == comparison.caseId }
        ?: return DifferenceEvidence(null, null, null)
    val expected = snapshot.groundTruth.observations.singleOrNull { it.observationId == difference.observationId }
        ?: return DifferenceEvidence(null, null, snapshot)
    val expectedPages = expected.evidence.filter { it.field == field }.map { it.region.page }.distinct()
    val expectedSource = DifferenceSource(expected.observationId, field, expectedPages).takeIf { it.pages.isNotEmpty() }
    if (comparison.kind != MeasurementKind.END_TO_END ||
        comparison.comparatorVersion != ExtractionComparator.VERSION || run.parserVersion != BiochemistryParser.VERSION)
        return DifferenceEvidence(null, expectedSource, snapshot)
    val pair = ExtractionComparator().match(snapshot.groundTruth.observations, rows.map { it.observation }).pairs
        .singleOrNull { it.expectedId == expected.observationId }
    val original = rows.singleOrNull { it.observation.id == pair?.actualId }?.observation
    val originalPages = original?.fieldEvidence.orEmpty().filter { it.field == field }.map { it.region.page }.distinct()
    return DifferenceEvidence(original?.let { DifferenceSource(it.id, field, originalPages) }?.takeIf { it.pages.isNotEmpty() },
        expectedSource, snapshot)
}
