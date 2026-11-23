package br.com.exames.inferencelocal

import br.com.exames.domain.inference.*
import kotlinx.coroutines.runBlocking
import java.io.File

class InferenceProcessService : OneShotInferenceService() {
    override fun generate(request: GenerationRequest, generating: () -> Unit): GenerationResult {
        RuntimeSampler(this).use { sampler ->
        val model = File(noBackupFilesDir, "models/gemma4.litertlm")
        val prepared = try { runBlocking { BundledModelStore(this@InferenceProcessService).prepare() } }
        catch (failure: ModelPreparationException) {
            return GenerationResult.Failed(when (failure.reason) {
                ModelPreparationFailure.MISSING_ASSET -> if (model.exists()) InferenceFailure.MODEL_CORRUPT else InferenceFailure.MODEL_UNAVAILABLE
                ModelPreparationFailure.CORRUPT_ASSET -> InferenceFailure.MODEL_CORRUPT
                ModelPreparationFailure.INSUFFICIENT_SPACE -> InferenceFailure.MODEL_SPACE
                ModelPreparationFailure.STORAGE_UNAVAILABLE -> InferenceFailure.MODEL_STORAGE
            })
        }
        val output = FreshGemmaEngine.generate(
            prepared.absolutePath,
            request.instruction,
            request.documentText,
            request.imageBytes,
            request.imageMimeType,
            Runnable { generating() },
        )
        return GenerationResult.UnvalidatedFinal(output.text, output.loadMillis, output.generationMillis,
            sampler.snapshot(output.prefillTokens, output.decodedTokens, output.contextTokens))
        }
    }
}
