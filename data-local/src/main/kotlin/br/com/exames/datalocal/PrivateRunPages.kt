package br.com.exames.datalocal

import android.os.SystemClock
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.Document
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

class PrivateRunPages(private val loader: PrivatePageLoader, private val pdf: PdfPreviewRenderer,
    private val processor: ProcessBiochemistryPage,
    private val attempts: ValidationRepository? = null,
    private val sampler: (() -> PageSampler)? = null,
    /** Assisted scheduling stays closed until real protocol/bounded-runtime acceptance (4.10/4.12). */
    private val assistedCompatible: (ProcessingRun) -> Boolean = { false }) : RunPageProcessor {
    fun inferenceConfiguration() = processor.configuration()
    override fun supports(run: ProcessingRun) = run.outputBasis == OutputBasis.AUTOMATIC && run.extractorVersion == VERSION &&
        run.parserVersion == BiochemistryParser.VERSION && run.ruleVersion == DeterministicRangeEvaluator.RULE_VERSION &&
        when (run.mode) {
            ExtractionMode.BASELINE -> run.inference == null
            ExtractionMode.ASSISTED -> attempts != null && run.inference != null &&
                run.inference == processor.configuration() && assistedCompatible(run)
        }
    override suspend fun pageCount(document: Document): Int = withContext(Dispatchers.IO) {
        when (document.mimeType) {
            "application/pdf" -> pdf.open(document.id).use { it.pageCount }
            "image/png", "image/jpeg" -> 1
            else -> throw java.io.IOException("DOCUMENT_FORMAT_INVALID")
        }
    }
    override suspend fun process(run: ProcessingRun, page: Int): PageBatch = withContext(Dispatchers.IO) {
        check(supports(run)) { "RUN_CONFIGURATION_UNSUPPORTED" }
        val start = SystemClock.elapsedRealtime()
        val measurement = runCatching { sampler?.invoke() }.getOrNull()
        var attemptId: String? = null
        try {
            loader.load(run.documentId, page).use { source ->
                val output = processor.process(run.documentId, run.runId, page, source.text.fragments, run.mode) {
                    val id = UUID.randomUUID().toString()
                    attemptId = id
                    requireNotNull(attempts).startInferenceAttempt(run.runId, page, id)
                }
                val failure = when {
                    output.parsed.layout == null -> "PAGE_UNSUPPORTED_LAYOUT"
                    output.parsed.observations.isEmpty() -> "PAGE_NO_SUPPORTED_OBSERVATIONS"
                    else -> null
                }
                val checkpoint = PageCheckpoint(run.runId, page, run.mode,
                    if (failure == null) PageState.COMPLETE else PageState.FAILED,
                    SystemClock.elapsedRealtime() - start,
                    failure ?: output.inference.disposition.takeIf { output.inference.outcome == InferenceOutcome.FALLBACK }?.name,
                    output.inference.outcome, output.parsed.warnings.map { it.name }.toSet(), output.inference, attemptId,
                    measurements = runCatching { measurement?.finish() }.getOrNull())
                if (failure != null) return@withContext PageBatch(checkpoint, emptyList(), emptyList(), emptyList())
                PageBatch(checkpoint, output.parsed.observations.map { it.observation },
                    output.parsed.observations.mapNotNull { it.referenceForAssessment }, output.assessments)
            }
        } catch (cancelled: CancellationException) {
            attemptId?.let { id -> withContext(NonCancellable) {
                // Deletion wins: the repository never recreates a missing run/checkpoint.
                runCatching { attempts?.cancelInferenceAttempt(run.runId, page, id) }
            } }
            throw cancelled
        } catch (_: Exception) {
            PageBatch(PageCheckpoint(run.runId, page, run.mode, PageState.FAILED,
                SystemClock.elapsedRealtime() - start, "PAGE_PROCESSING_FAILED",
                if (run.mode == ExtractionMode.BASELINE) InferenceOutcome.NOT_ATTEMPTED else InferenceOutcome.FAILED,
                measurements = runCatching { measurement?.finish() }.getOrNull()), emptyList(), emptyList(), emptyList())
        }
    }
    companion object { const val VERSION = "private-page-pipeline/2-tesseract-por" }
}
