package br.com.exames.inferencelocal

import android.content.Context
import android.os.Build
import android.os.Process
import br.com.exames.domain.inference.*
import br.com.exames.domain.validation.ExtractionMode

/** Private one-request child process owns preparation, engine and native work. */
class LocalTextInference(context: Context) : TextInference {
    private var client = ProcessInferenceClient(context)
    internal constructor(context: Context, transport: ProcessInferenceClient) : this(context) { client = transport }
    override fun configuration(promptVersion: String, schemaVersion: String) =
        SelectedInferenceConfiguration.forProtocol(promptVersion, schemaVersion)
    override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
        if (mode == ExtractionMode.BASELINE) return GenerationResult.NotRequested
        if (Build.VERSION.SDK_INT < 24 || !Process.is64Bit()) return GenerationResult.Failed(InferenceFailure.UNSUPPORTED_DEVICE)
        return client.generate(request)
    }
    companion object {
        const val MODEL_BYTES = SelectedInferenceConfiguration.MODEL_BYTES
        const val MODEL_SHA256 = SelectedInferenceConfiguration.MODEL_SHA256
    }
}
