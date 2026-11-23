package br.com.exames.domain.extraction

import br.com.exames.domain.inference.GenerationResult
import br.com.exames.domain.inference.InferenceFailure
import br.com.exames.domain.inference.TextInference
import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.ExtractionMode
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
enum class ExamIssue { PAGE_UNREADABLE, INSUFFICIENT_POSITIONED_TEXT, INPUT_LIMIT, INFERENCE_FAILED, INVALID_RESPONSE, EVIDENCE_NEEDS_REVIEW }

@Serializable
class ExamPageOutcome(
    val page: Int,
    val observations: List<ExamObservation>,
    val issues: Set<ExamIssue>,
    val inferenceFailures: Set<InferenceFailure>,
)

@Serializable
class ExamExtraction(val pages: List<ExamPageOutcome>) {
    val partial: Boolean get() = pages.any { it.issues.isNotEmpty() }
}

class ExtractExam(private val inference: TextInference) {
    suspend fun extract(
        pages: IntRange,
        read: suspend (Int) -> List<SourceFragment>,
        onProgress: (page: Int, block: Int, blocks: Int) -> Unit = { _, _, _ -> },
        onPageFinished: (ExamPageOutcome) -> Unit = {},
    ): ExamExtraction {
        require(!pages.isEmpty() && pages.first >= 1 && pages.last <= MAX_PAGES)
        val outcomes = mutableListOf<ExamPageOutcome>()
        var attempted = 0
        var unavailable: InferenceFailure? = null
        for (page in pages) {
            currentCoroutineContext().ensureActive()
            onProgress(page, 0, 0)
            val fragments = try {
                read(page)
            } catch (_: IOException) {
                val failed = ExamPageOutcome(page, emptyList(), setOf(ExamIssue.PAGE_UNREADABLE), emptySet())
                outcomes += failed
                onPageFinished(failed)
                continue
            }
            val evidence = ExamEvidence.prepare(page, fragments)
            val issues = mutableSetOf<ExamIssue>()
            val failures = mutableSetOf<InferenceFailure>()
            val verified = linkedMapOf<String, ExamObservation>()
            val conflicts = mutableSetOf<String>()
            if (evidence.needsReview) issues += ExamIssue.INSUFFICIENT_POSITIONED_TEXT
            val blocks = ArrayDeque(ExamProtocol.blocks(evidence.rows))
            var processed = 0
            while (blocks.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val block = blocks.removeFirst()
                processed++
                onProgress(page, processed, processed + blocks.size)
                if (block == null || processed > MAX_BLOCKS_PER_PAGE || attempted >= MAX_DOCUMENT_REQUESTS) {
                    issues += ExamIssue.INPUT_LIMIT
                    continue
                }
                val failure = unavailable
                val response = if (failure != null) {
                    GenerationResult.Failed(failure)
                } else {
                    attempted++
                    inference.generate(ExtractionMode.ASSISTED, block.request)
                }
                currentCoroutineContext().ensureActive()
                when (response) {
                    is GenerationResult.UnvalidatedFinal -> {
                        val checked = ExamProtocol.validate(block, response.text)
                        for (row in checked.observations) {
                            val key = row.sourceResult.id
                            if (key in conflicts) continue
                            val prior = verified.putIfAbsent(key, row)
                            if (prior != null) {
                                if (prior.result != row.result || prior.unit != row.unit ||
                                    (prior.reference != null && row.reference != null && prior.reference.id != row.reference.id)) {
                                    verified.remove(key)
                                    conflicts += key
                                    issues += ExamIssue.EVIDENCE_NEEDS_REVIEW
                                } else if (prior.reference == null && row.reference != null) {
                                    verified[key] = row
                                }
                            }
                        }
                        val missing = block.rows.filter { source ->
                            val published = verified[source.result.id]
                            published == null || source.reference != null && published.reference == null
                        }
                        val slots = MAX_BLOCKS_PER_PAGE - processed - blocks.size
                        if (missing.isNotEmpty() && block.rows.size > 1 && slots > 0) {
                            val size = minOf(missing.size, (block.rows.size + 1) / 2)
                            val retries = missing.chunked(size).flatMap(ExamProtocol::blocks)
                            if (retries.size <= slots) {
                                blocks.addAll(retries)
                            } else {
                                checked.issue?.let(issues::add)
                            }
                        } else if (checked.issue != null && (missing.isNotEmpty() || checked.observations.any { it.referenceNeedsReview })) {
                            issues += checked.issue
                        }
                    }
                    is GenerationResult.Failed -> {
                        issues += ExamIssue.INFERENCE_FAILED
                        failures += response.reason
                        if (response.reason in configurationFailures) unavailable = response.reason
                    }
                    GenerationResult.NotRequested -> issues += ExamIssue.INFERENCE_FAILED
                }
            }
            val ordered = evidence.rows.mapNotNull { verified[it.result.id] }.distinctBy { it.sourceResult.id }
            val outcome = ExamPageOutcome(page, ordered, issues, failures)
            outcomes += outcome
            onPageFinished(outcome)
        }
        currentCoroutineContext().ensureActive()
        return ExamExtraction(outcomes)
    }

    companion object {
        const val MAX_PAGES = 20
        private const val MAX_BLOCKS_PER_PAGE = 12
        private const val MAX_DOCUMENT_REQUESTS = 64
        private val configurationFailures = setOf(
            InferenceFailure.UNSUPPORTED_DEVICE, InferenceFailure.MODEL_UNAVAILABLE, InferenceFailure.MODEL_CORRUPT,
            InferenceFailure.MODEL_SPACE, InferenceFailure.MODEL_STORAGE,
        )
    }
}
