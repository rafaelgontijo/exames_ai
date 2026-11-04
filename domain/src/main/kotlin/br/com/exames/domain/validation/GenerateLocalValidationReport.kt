package br.com.exames.domain.validation

/** Caller selects immutable runs/truth hashes explicitly. Automatic reads never apply manual corrections. */
data class ReportSelection(val case: CorpusCase, val runId: String, val truthSha256: String)

class GenerateLocalValidationReport(private val repository: ValidationRepository) {
    suspend fun generate(selections: List<ReportSelection>): LocalValidationReport {
        val samples = selections.map { selection ->
            val run = requireNotNull(repository.findRun(selection.runId)) { "REPORT_RUN_UNAVAILABLE" }
            val truth = requireNotNull(repository.groundTruth(run.documentId).singleOrNull { it.sha256 == selection.truthSha256 }) {
                "REPORT_TRUTH_UNAVAILABLE"
            }
            val observations = repository.observations(run.runId)
            val references = observations.mapNotNull { observation ->
                repository.reference(observation.id)?.let { observation.id to it }
            }.toMap()
            val assessments = repository.assessments(run.runId)
            require(assessments.map { it.observationId }.distinct().size == assessments.size) { "REPORT_DUPLICATE_ASSESSMENT" }
            ReportSample(selection.case, truth, run, observations, references, assessments.associateBy { it.observationId },
                run.outputBasis, run.correctionIds)
        }
        return ValidationReportGenerator().generate(samples)
    }
}
