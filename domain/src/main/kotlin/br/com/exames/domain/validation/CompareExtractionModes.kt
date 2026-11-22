package br.com.exames.domain.validation

import br.com.exames.domain.model.AssessmentStatus
import kotlinx.serialization.Serializable

@Serializable
data class PairDivergence(val expectedId: String, val baselineStatus: String?, val assistedStatus: String?,
    val fields: Set<String>, val baselinePresent: Boolean, val assistedPresent: Boolean)
@Serializable
data class RunPerformance(val runId: String, val deviceModel: String, val androidVersion: String,
    val outcome: InferenceOutcome, val pages: List<PageCheckpoint>, val acceptedModelPages: Int, val fallbackOrFailedPages: Int)
@Serializable
data class PairedValidationReport(val report: LocalValidationReport, val baseline: RunPerformance, val assisted: RunPerformance,
    val truthSha256: String, val divergences: List<PairDivergence>, val newErrors: List<String>,
    val newAbnormalShownWithin: List<String>, val numericCoverageDelta: Long,
    val limitations: List<String> = listOf("TECHNICAL_CORPUS_NOT_CLINICAL_VALIDATION", "PSS_SAMPLED_NOT_PEAK_GPU_ACCOUNTING_INCOMPLETE",
        "THOUGHT_FINAL_TOKEN_COUNTS_UNAVAILABLE_IN_SDK_TOTAL_DECODE_REPORTED", "FRESH_ENGINE_EACH_REQUEST_OS_CACHE_UNCONTROLLED_NO_WARM_ENGINE_REUSE"))

/** Explicit pair, exact source/truth/rule; corrected outputs cannot improve automatic A/B results. */
class CompareExtractionModes(private val repository: ValidationRepository) {
    suspend fun compare(baselineId: String, assistedId: String, truthHash: String): PairedValidationReport {
        val b=requireNotNull(repository.findRun(baselineId)); val a=requireNotNull(repository.findRun(assistedId))
        require(b.runId!=a.runId && b.mode==ExtractionMode.BASELINE && a.mode==ExtractionMode.ASSISTED)
        require(listOf(a,b).all { it.outputBasis==OutputBasis.AUTOMATIC && it.correctionIds.isEmpty() && it.state !in setOf(RunState.QUEUED,RunState.RUNNING) })
        require(a.documentId==b.documentId && a.documentSha256==b.documentSha256 && a.caseId==b.caseId &&
            a.ruleVersion==b.ruleVersion && a.parserVersion==b.parserVersion && a.extractorVersion==b.extractorVersion)
        val document=requireNotNull(repository.find(b.documentId)); require(document.hash==b.documentSha256)
        val case=requireNotNull(document.corpusCase) { "PAIR_CORPUS_METADATA_REQUIRED" }
        val truth=requireNotNull(repository.groundTruth(document.id).singleOrNull { it.sha256==truthHash })
        require(truth.groundTruth.hasReviewedMetadata() && truth.groundTruth.caseId==case.caseId)
        val base=MeasureValidation(repository).measure(baselineId,truthHash)
        val assist=MeasureValidation(repository).measure(assistedId,truthHash)
        val bStatus=repository.assessments(baselineId).associate { it.observationId to it.status }
        val aStatus=repository.assessments(assistedId).associate { it.observationId to it.status }
        val bCompare=requireNotNull(base.endToEnd.comparison); val aCompare=requireNotNull(assist.endToEnd.comparison)
        fun fields(c: ExtractionComparison) = (c.raw.map { "raw.${it.field}" to it } + c.canonical.map { "canonical.${it.field}" to it })
            .associate { (name,value) -> (value.expectedId to name) to value.actual }
        val baseFields=fields(bCompare); val assistFields=fields(aCompare)
        val bPairs=bCompare.matching.pairs.associate { it.expectedId to it.actualId }; val aPairs=aCompare.matching.pairs.associate { it.expectedId to it.actualId }
        val divergences=mutableListOf<PairDivergence>(); val newErrors=mutableListOf<String>(); val newWithin=mutableListOf<String>()
        for (expected in truth.groundTruth.observations) {
            val bi=bPairs[expected.observationId]; val ai=aPairs[expected.observationId]
            val bs=bi?.let { bStatus[it] }; val ass=ai?.let { aStatus[it] }
            val changed=(baseFields.keys+assistFields.keys).filter { it.first==expected.observationId && baseFields[it]!=assistFields[it] }.map { it.second }.toSet()
            if (changed.isNotEmpty() || bs!=ass || (bi==null)!=(ai==null)) divergences+=PairDivergence(expected.observationId,bs?.name,ass?.name,changed,bi!=null,ai!=null)
            if (bs?.name==expected.expectedStatus.name && ass?.name!=expected.expectedStatus.name) newErrors+=expected.observationId
            if (expected.expectedStatus in setOf(ExpectedStatus.ABOVE_RANGE,ExpectedStatus.BELOW_RANGE) &&
                bs!=AssessmentStatus.WITHIN_RANGE && ass==AssessmentStatus.WITHIN_RANGE) newWithin+=expected.observationId
        }
        suspend fun performance(run: ProcessingRun): RunPerformance {
            val pages=repository.checkpoints(run.runId)
            return RunPerformance(run.runId,run.deviceModel,run.androidVersion,run.inferenceOutcome,pages,
                pages.count { run.inference!=null && it.inferenceOutcome==InferenceOutcome.SUCCEEDED &&
                    it.inferenceAttempt?.let { a -> a.requestSubmitted && a.configuration==run.inference && a.disposition==br.com.exames.domain.inference.ProposalDisposition.ACCEPTED }==true },
                pages.count { run.mode==ExtractionMode.ASSISTED && it.inferenceOutcome!=InferenceOutcome.SUCCEEDED })
        }
        val report=GenerateLocalValidationReport(repository).generate(listOf(ReportSelection(case,baselineId,truthHash),ReportSelection(case,assistedId,truthHash)))
        return PairedValidationReport(report,performance(b),performance(a),truthHash,divergences,newErrors,newWithin,
            assist.endToEnd.numericCoverage.numerator-base.endToEnd.numericCoverage.numerator)
    }
}
