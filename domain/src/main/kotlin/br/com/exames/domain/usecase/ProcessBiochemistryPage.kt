package br.com.exames.domain.usecase

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.inference.*
import br.com.exames.domain.model.Assessment
import br.com.exames.domain.parser.*
import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.domain.validation.InferenceOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ProcessedBiochemistryPage(val parsed: ParsedPage, val assessments: List<Assessment>,
    val inference: PageInferenceAttempt)

/** Source only, no truth/history. The model's final text is discarded after validation, never evaluated directly. */
class ProcessBiochemistryPage(private val inference: TextInference,
    private val parser: BiochemistryParser = BiochemistryParser(),
    private val evaluator: DeterministicRangeEvaluator = DeterministicRangeEvaluator()) {
    fun configuration() = inference.configuration(ProposalProtocol.PROMPT_VERSION, ProposalProtocol.SCHEMA_VERSION)
    suspend fun process(documentId: String, runId: String, page: Int, fragments: List<SourceFragment>,
        mode: ExtractionMode, beforeSubmission: suspend () -> Unit = {}): ProcessedBiochemistryPage {
        currentCoroutineContext().ensureActive()
        val baseline = parser.parse(documentId, runId, page, fragments)
        var parsed = baseline
        val attempt = if (mode == ExtractionMode.BASELINE)
            PageInferenceAttempt(ProposalDisposition.NOT_REQUESTED, InferenceOutcome.NOT_ATTEMPTED)
        else {
            val configuration = configuration()
            val prompt = try { ProposalProtocol.compile(page, fragments) }
                catch (_: ProposalInputLimitException) { null }
            if (prompt != null) {
                // A durable journal must succeed before invoking a model; storage errors are not model failures.
                beforeSubmission()
                currentCoroutineContext().ensureActive()
            }
            val output = try {
                if (prompt == null) GenerationResult.Failed(InferenceFailure.INPUT_LIMIT)
                else inference.generate(mode, prompt.request)
            }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { GenerationResult.Failed(InferenceFailure.RUNTIME_FAILURE) }
            // A backend that returns after cancellation cannot authorize evaluation or a later page commit.
            currentCoroutineContext().ensureActive()
            val result = when (output) {
                GenerationResult.NotRequested -> PageInferenceAttempt(ProposalDisposition.NO_FINAL, InferenceOutcome.FALLBACK,
                    requestSubmitted = true)
                is GenerationResult.Failed -> PageInferenceAttempt(ProposalDisposition.ENGINE_FAILURE,
                    InferenceOutcome.FALLBACK, output.reason, requestSubmitted = prompt != null)
                is GenerationResult.UnvalidatedFinal -> {
                    when (val decoded = ProposalProtocol.decode(output.text)) {
                        ProposalDecode.Invalid -> PageInferenceAttempt(ProposalDisposition.INVALID_SCHEMA, InferenceOutcome.FALLBACK,
                            loadMillis = output.loadMillis, generationMillis = output.generationMillis, requestSubmitted = true)
                        is ProposalDecode.Valid -> {
                            val validated = ProposalEvidenceValidator().validate(requireNotNull(prompt), decoded.proposal, baseline)
                            // Semantic conflicts preserve the source but add a review barrier before the shared evaluator.
                            parsed = baseline.copy(observations = validated.observations)
                            val accepted = validated.acceptedProposal != null
                            PageInferenceAttempt(if (accepted) ProposalDisposition.ACCEPTED else ProposalDisposition.INVALID_EVIDENCE,
                                if (accepted) InferenceOutcome.SUCCEEDED else InferenceOutcome.FALLBACK,
                                issues = validated.issues, loadMillis = output.loadMillis, generationMillis = output.generationMillis,
                                requestSubmitted = true)
                        }
                    }
                }
            }
            result.copy(configuration = configuration, measurements = (output as? GenerationResult.UnvalidatedFinal)?.measurements)
        }
        currentCoroutineContext().ensureActive()
        return ProcessedBiochemistryPage(parsed, parsed.observations.map {
            evaluator.evaluate(it.observation, it.referenceForAssessment)
        }, attempt)
    }
}
