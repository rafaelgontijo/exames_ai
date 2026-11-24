package br.com.exames.app

import br.com.exames.domain.inference.GenerationRequest
import br.com.exames.domain.inference.GenerationResult
import br.com.exames.domain.inference.InferenceExecutionTarget
import br.com.exames.domain.inference.InferenceFailure
import br.com.exames.domain.inference.RoutingDecision
import br.com.exames.domain.inference.RoutingReason
import br.com.exames.domain.inference.TextInference
import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.domain.validation.InferenceConfiguration

/**
 * Unified inference router implementing TextInference.
 * Dispatches strictly according to the pre-evaluated RoutingDecision: LOCAL runs on-device without
 * network; CLOUD_FALLBACK runs only after explicit opt-in consent; blocked decisions never call network.
 */
internal class HybridTextInference(
    private val decision: RoutingDecision,
    private val local: TextInference,
    private val cloud: TextInference,
) : TextInference {
    override fun configuration(promptVersion: String, schemaVersion: String): InferenceConfiguration? =
        when (decision.target) {
            InferenceExecutionTarget.LOCAL -> local.configuration(promptVersion, schemaVersion)
            InferenceExecutionTarget.CLOUD_FALLBACK -> cloud.configuration(promptVersion, schemaVersion)
            InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED,
            InferenceExecutionTarget.BLOCKED_OFFLINE -> null
        }

    override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
        if (mode == ExtractionMode.BASELINE) return GenerationResult.NotRequested
        return when (decision.target) {
            InferenceExecutionTarget.LOCAL -> local.generate(mode, request)
            InferenceExecutionTarget.CLOUD_FALLBACK -> cloud.generate(mode, request)
            InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED ->
                GenerationResult.Failed(InferenceFailure.CLOUD_CONSENT_REQUIRED)
            InferenceExecutionTarget.BLOCKED_OFFLINE -> {
                val failure = when (decision.reason) {
                    RoutingReason.CLOUD_NETWORK_UNAVAILABLE -> InferenceFailure.NETWORK_UNAVAILABLE
                    RoutingReason.HARDWARE_NOT_64BIT,
                    RoutingReason.HARDWARE_NO_GPU,
                    RoutingReason.HARDWARE_RAM_BELOW_MINIMUM,
                    RoutingReason.HARDWARE_AVAILABLE_RAM_LOW -> InferenceFailure.UNSUPPORTED_DEVICE
                    RoutingReason.WEIGHTS_INSUFFICIENT_SPACE -> InferenceFailure.MODEL_SPACE
                    RoutingReason.WEIGHTS_CORRUPT -> InferenceFailure.MODEL_CORRUPT
                    else -> InferenceFailure.MODEL_UNAVAILABLE
                }
                GenerationResult.Failed(failure)
            }
        }
    }
}
