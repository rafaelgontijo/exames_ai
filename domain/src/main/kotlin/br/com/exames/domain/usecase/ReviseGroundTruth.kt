package br.com.exames.domain.usecase

import br.com.exames.domain.validation.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.time.Instant

/** Independently authored answers only. Never reads extraction, evaluates, or generates expected statuses. */
class ReviseGroundTruth(private val repository: ValidationRepository) {
    suspend fun revise(documentId: String, parentHash: String, draft: String): GroundTruthSnapshot {
        val document = requireNotNull(repository.find(documentId)) { "GROUND_TRUTH_DOCUMENT_UNAVAILABLE" }
        val snapshots = repository.groundTruth(documentId)
        val parent = requireNotNull(snapshots.singleOrNull { it.sha256 == parentHash }) { "GROUND_TRUTH_PARENT_UNAVAILABLE" }
        val truth = GroundTruthDraft.decode(draft, requireNotNull(document.pageCount))
        require(truth.caseId == parent.groundTruth.caseId && truth.version != parent.groundTruth.version) { "GROUND_TRUTH_REVISION_IDENTITY" }
        val bytes = ValidationJson.format.encodeToString(truth).toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val revision = GroundTruthSnapshot(hash, truth, parentHash)
        snapshots.singleOrNull { it.groundTruth.caseId == truth.caseId && it.groundTruth.version == truth.version }?.let {
            require(it == revision) { "GROUND_TRUTH_VERSION_EXISTS" }
            return it
        }
        repository.appendGroundTruth(documentId, revision)
        return revision
    }
}

object GroundTruthDraft {
    const val MAX_BYTES = 1_048_576
    fun validateJson(text: String, maxBytes: Int = MAX_BYTES) {
        require(text.length <= maxBytes && text.toByteArray(Charsets.UTF_8).size <= maxBytes)
        JsonKeyGuard(text).validate()
    }
    fun decode(text: String, pageCount: Int): GroundTruthVersion {
        require(pageCount in 1..20 && text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "GROUND_TRUTH_DRAFT_LIMIT" }
        // Kotlin serialization accepts repeated keys. Reject them before decoding a human-authored draft.
        validateJson(text)
        val truth = ValidationJson.format.decodeFromString<GroundTruthVersion>(text)
        require(truth.schemaVersion in setOf("1.0", "1.1"))
        require(truth.caseId.isNotBlank() && truth.caseId.length <= 128 && truth.version.isNotBlank() && truth.version.length <= 128)
        require(truth.observations.size in 1..500 && truth.observations.map { it.observationId }.distinct().size == truth.observations.size)
        if (truth.reviewState == ReviewState.REVIEWED) {
            require(!truth.reviewerId.isNullOrBlank() && truth.reviewerId.length <= 256)
            require(runCatching { Instant.parse(requireNotNull(truth.reviewedAt)) }.isSuccess) { "GROUND_TRUTH_REVIEW_TIME_INVALID" }
        } else require(truth.reviewerId == null && truth.reviewedAt == null)
        val decimal = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
        fun number(value: String?) { require(value == null || value.length <= 512 && decimal.matches(value)) }
        for (row in truth.observations) {
            require(row.observationId.isNotBlank() && row.observationId.length <= 128)
            require(row.analyteRaw.isNotBlank() && row.resultRaw.isNotBlank() && row.expectedReason.isNotBlank())
            require(listOf(row.analyteRaw, row.resultRaw, row.unitRaw, row.referenceRaw, row.expectedReason).all { it == null || it.length <= 4096 })
            number(row.numericValue); number(row.low); number(row.high)
            require(row.evidence.size <= 100 && row.evidence.all { it.region.page <= pageCount &&
                it.field in setOf("analyteRaw", "resultRaw", "unitRaw", "referenceRaw") && it.raw.length <= 4096 })
            require((truth.schemaVersion == "1.1") == (row.evaluationInput != null))
            row.evaluationInput?.let { input ->
                require(input.ruleVersion.length <= 128)
                val references = listOfNotNull(input.selectedReference) + input.candidates.map { it.metadata }
                require(references.all { it.observationId == row.observationId && it.sourcePage <= pageCount })
                input.candidates.forEach { number(it.low); number(it.high) }
            }
        }
        return truth
    }

    /** Bounded structural pass; the strict serializer validates scalar tokens and all field types. */
    private class JsonKeyGuard(private val source: String) {
        private var at = 0
        private var nodes = 0
        fun validate() { value(0); space(); require(at == source.length) }
        private fun space() { while (at < source.length && source[at] in " \t\n\r") at++ }
        private fun take(c: Char): Boolean { space(); return if (at < source.length && source[at] == c) { at++; true } else false }
        private fun string(): String {
            space(); val start = at; require(at < source.length && source[at++] == '"')
            while (at < source.length) {
                when (source[at++]) {
                    '\\' -> { require(at < source.length); at++ }
                    '"' -> return ValidationJson.format.decodeFromString(source.substring(start, at))
                }
            }
            error("GROUND_TRUTH_JSON_STRING")
        }
        private fun value(depth: Int) {
            require(depth <= 24 && ++nodes <= 100_000) { "GROUND_TRUTH_JSON_LIMIT" }
            space(); require(at < source.length)
            when (source[at]) {
                '{' -> {
                    at++; val keys = mutableSetOf<String>()
                    if (!take('}')) do {
                        require(keys.add(string())) { "GROUND_TRUTH_DUPLICATE_KEY" }
                        require(take(':')); value(depth + 1)
                        if (take('}')) return
                        require(take(','))
                    } while (true)
                }
                '[' -> {
                    at++
                    if (!take(']')) do {
                        value(depth + 1)
                        if (take(']')) return
                        require(take(','))
                    } while (true)
                }
                '"' -> string()
                else -> {
                    val start = at
                    while (at < source.length && source[at] !in ",]} \t\n\r") at++
                    require(at > start)
                }
            }
        }
    }
}
