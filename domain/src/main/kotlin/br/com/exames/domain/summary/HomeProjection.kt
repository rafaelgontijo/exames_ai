package br.com.exames.domain.summary

import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import java.time.Instant

enum class DocumentFormat { PDF, JPEG, PNG, OTHER }

/** Latest automatic extraction for one mode. State is that run's own state; older runs never stand in for it. */
data class ModeExtraction(val mode: ExtractionMode, val runId: String, val state: RunState, val inferenceOutcome: InferenceOutcome, val startedAt: String)

data class HomeDocumentCard(
    val documentId: String,
    val format: DocumentFormat,
    val importedAt: Instant,
    /** Empty means no automatic run exists, not that processing succeeded. Ordered BASELINE then ASSISTED. */
    val extractions: List<ModeExtraction>,
    /** Corrected/reassessed runs are counted separately and never replace the automatic summary. */
    val derivedRunCount: Int,
)

sealed interface HomeState {
    /** The store answered and holds no (visible) documents. */
    data object Empty : HomeState
    /** The store could not be read; distinct from Empty so no false "no documents" is shown. */
    data object Unavailable : HomeState
    data class Loaded(val cards: List<HomeDocumentCard>) : HomeState
}

object ProjectHome {
    /** [documents] null = store unavailable. [runsByDocument] null for a document = its runs could not be read. */
    fun project(documents: List<Document>?, runsByDocument: Map<String, List<ProcessingRun>?>): HomeState {
        if (documents == null) return HomeState.Unavailable
        val visible = documents.filter { it.processingState != ProcessingState.DELETING }
        if (visible.isEmpty()) return HomeState.Empty
        val cards = visible.sortedWith(compareByDescending<Document> { it.importedAt }.thenBy { it.id }).map { d ->
            val runs = runsByDocument[d.id].orEmpty().filter { it.documentId == d.id }
            val extractions = ExtractionMode.entries.mapNotNull { mode ->
                runs.filter { it.mode == mode && it.outputBasis == OutputBasis.AUTOMATIC }
                    .maxWithOrNull(compareBy<ProcessingRun> { started(it) }.thenBy { it.runId })
                    ?.let { ModeExtraction(mode, it.runId, it.state, it.inferenceOutcome, it.startedAt) }
            }
            HomeDocumentCard(d.id, format(d.mimeType), d.importedAt, extractions, runs.count { it.outputBasis != OutputBasis.AUTOMATIC })
        }
        return HomeState.Loaded(cards)
    }

    private fun started(run: ProcessingRun): Instant = runCatching { Instant.parse(run.startedAt) }.getOrDefault(Instant.MIN)
    private fun format(mime: String) = when (mime.lowercase()) {
        "application/pdf" -> DocumentFormat.PDF; "image/jpeg" -> DocumentFormat.JPEG; "image/png" -> DocumentFormat.PNG
        else -> DocumentFormat.OTHER
    }
}
