package br.com.exames.domain.model

import java.time.Instant

data class Document(
    val id: String,
    val uriLocal: String,
    val hash: String,
    val mimeType: String,
    val importedAt: Instant,
    val pageCount: Int?,
    val processingState: ProcessingState = ProcessingState.IMPORTED,
)
enum class ProcessingState { IMPORTED, PROCESSING, AWAITING_REVIEW, COMPLETED, FAILED, DELETING }
