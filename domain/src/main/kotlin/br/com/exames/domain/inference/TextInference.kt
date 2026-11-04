package br.com.exames.domain.inference

import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.domain.validation.InferenceConfiguration
import kotlinx.serialization.Serializable

/** Text only. Expected answers, images, clinical classifications and previous sessions are absent. */
class GenerationRequest(val instruction: String, val documentText: String,
    val promptVersion: String, val schemaVersion: String)

@Serializable enum class InferenceFailure { UNSUPPORTED_DEVICE, MODEL_UNAVAILABLE, MODEL_CORRUPT, MODEL_SPACE, MODEL_STORAGE,
    RUNTIME_FAILURE, EMPTY_FINAL, INPUT_LIMIT, OUTPUT_LIMIT, LOAD_TIMEOUT, GENERATION_TIMEOUT, PROCESS_DIED, BUSY, PROCESS_UNAVAILABLE }
sealed interface GenerationResult {
    data object NotRequested : GenerationResult
    data class Failed(val reason: InferenceFailure) : GenerationResult
    /** Untrusted final channel; MUST pass schema/evidence/association validation before assessment. */
    class UnvalidatedFinal(val text: String, val loadMillis: Long, val generationMillis: Long,
        val measurements: br.com.exames.domain.validation.RuntimeMeasurements? = null) : GenerationResult
}

interface TextInference {
    /** No model work. Null means the adapter cannot attest its configuration for production scheduling. */
    fun configuration(promptVersion: String, schemaVersion: String): InferenceConfiguration? = null
    suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult
}
