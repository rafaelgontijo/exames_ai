package br.com.exames.inferencelocal

import android.os.Bundle
import br.com.exames.domain.inference.*
import br.com.exames.domain.validation.RuntimeMeasurements
import br.com.exames.domain.validation.ValidationJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

internal object InferenceProcessProtocol {
    const val HELLO = 1; const val READY = 2; const val RUN = 3; const val GENERATING = 4; const val FINAL = 5
    const val LOAD_MS = 120000L; const val GENERATION_MS = 120000L; const val REAP_MS = 5000L
    fun request(request: GenerationRequest, nonce: String) = Bundle().apply {
        putString("nonce", nonce); putString("instruction", request.instruction); putString("document", request.documentText)
        putString("prompt", request.promptVersion); putString("schema", request.schemaVersion)
        if (request.imageBytes != null && request.imageMimeType != null) {
            putByteArray("imageBytes", request.imageBytes)
            putString("imageMimeType", request.imageMimeType)
        }
    }
    fun decodeRequest(bundle: Bundle) = GenerationRequest(
        bundle.getString("instruction").orEmpty(),
        bundle.getString("document").orEmpty(),
        bundle.getString("prompt").orEmpty(),
        bundle.getString("schema").orEmpty(),
        bundle.getByteArray("imageBytes"),
        bundle.getString("imageMimeType"),
    )
    fun result(result: GenerationResult, nonce: String) = Bundle().apply {
        putString("nonce", nonce)
        when (result) {
            is GenerationResult.UnvalidatedFinal -> {
                if (result.text.length > InferencePayloadLimits.MAX_FINAL_CHARACTERS) putString("failure", InferenceFailure.OUTPUT_LIMIT.name)
                else {
                    putString("text", result.text); putLong("load", result.loadMillis); putLong("generation", result.generationMillis)
                    result.measurements?.let { putString("measurements", ValidationJson.format.encodeToString(it)) }
                }
            }
            is GenerationResult.Failed -> putString("failure", result.reason.name)
            GenerationResult.NotRequested -> putString("failure", InferenceFailure.RUNTIME_FAILURE.name)
        }
    }
    fun decodeResult(bundle: Bundle): GenerationResult {
        bundle.getString("failure")?.let { name ->
            return GenerationResult.Failed(InferenceFailure.entries.firstOrNull { it.name == name } ?: InferenceFailure.RUNTIME_FAILURE)
        }
        val text = bundle.getString("text") ?: return GenerationResult.Failed(InferenceFailure.EMPTY_FINAL)
        if (text.length > InferencePayloadLimits.MAX_FINAL_CHARACTERS) return GenerationResult.Failed(InferenceFailure.OUTPUT_LIMIT)
        if (text.isBlank()) return GenerationResult.Failed(InferenceFailure.EMPTY_FINAL)
        val load = bundle.getLong("load", -1); val generation = bundle.getLong("generation", -1)
        if (load < 0 || generation < 0) return GenerationResult.Failed(InferenceFailure.RUNTIME_FAILURE)
        val measurements = bundle.getString("measurements")?.let { raw ->
            if (raw.length > 4096) return GenerationResult.Failed(InferenceFailure.RUNTIME_FAILURE)
            try { ValidationJson.format.decodeFromString<RuntimeMeasurements>(raw) }
            catch (_: Exception) { return GenerationResult.Failed(InferenceFailure.RUNTIME_FAILURE) }
        }
        return GenerationResult.UnvalidatedFinal(text, load, generation, measurements)
    }
}
