package br.com.exames.inferencelocal

import br.com.exames.domain.inference.InferencePayloadLimits
import br.com.exames.domain.inference.ProposalProtocol
import br.com.exames.domain.validation.InferenceConfiguration

/** Fixed production identity. Mirrors the staged selection/lock; no clinical text or model loading. */
object SelectedInferenceConfiguration {
    const val PRODUCTION_PROTOCOL_ACCEPTED = true
    fun referenceDeviceCompatible(): Boolean = android.os.Build.MODEL == "SM-S931B" && android.os.Build.VERSION.SDK_INT >= 24 &&
        android.os.Process.is64Bit() && "arm64-v8a" in android.os.Build.SUPPORTED_ABIS
    const val MODEL_ID = "gemma4"
    const val MODEL_REVISION = "2e12bfd869b2962984f97a1f2a26807316d534cc"
    const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val MODEL_BYTES = 2588147712L
    const val RUNTIME_VERSION = "com.google.ai.edge.litertlm:litertlm-android:0.18.1"
    const val RUNTIME_AAR_SHA256 = "05c6bdc8acda834efcca12c0ad5d06d2cd8c83e60770f056d8d222f699f3012b"
    const val VISION_BACKEND = "GPU"
    const val CONTEXT_TOKENS = 4096
    const val OUTPUT_TOKENS = 1536
    const val MAX_NUM_IMAGES = 1
    const val MAX_IMAGE_BYTES = InferencePayloadLimits.MAX_IMAGE_BYTES
    const val TOP_K = 1
    const val TOP_P = 1.0
    const val TEMPERATURE = 0.0
    const val SEED = 0
    const val NATIVE_CACHE = ":nocache"

    fun forProtocol(promptVersion: String = ProposalProtocol.PROMPT_VERSION,
        schemaVersion: String = ProposalProtocol.SCHEMA_VERSION) = InferenceConfiguration(
        MODEL_ID, MODEL_REVISION, MODEL_SHA256,
        "Gemma 4 mobile mixed 2/4/8-bit scheme; artifact-specific details not independently inspected",
        RUNTIME_VERSION, promptVersion, schemaVersion, "GPU", CONTEXT_TOKENS, OUTPUT_TOKENS,
        thinkingBudget = null, timeoutMillis = InferenceProcessProtocol.GENERATION_MS,
        generationParameters = mapOf(
            "api" to "synchronous", "topK" to TOP_K.toString(), "topP" to TOP_P.toString(),
            "visionBackend" to VISION_BACKEND, "maxNumImages" to MAX_NUM_IMAGES.toString(),
            "maxImageBytes" to MAX_IMAGE_BYTES.toString(),
            "runtimeAarSha256" to RUNTIME_AAR_SHA256,
            "temperature" to TEMPERATURE.toString(), "seed" to SEED.toString(), "tools" to "none",
            "nativeResponseFormat" to "false", "channelPolicy" to "bundle-metadata-final-only",
            "thinkingConfig" to "runtime-default", "nativeCache" to NATIVE_CACHE,
            "lifecycle" to "process-isolation/1", "freshEnginePerRequest" to "true",
            "freshProcessPerRequest" to "true", "activationDataType" to "runtime-default",
            "loadTimeoutMillis" to InferenceProcessProtocol.LOAD_MS.toString(),
            "generationTimeoutMillis" to InferenceProcessProtocol.GENERATION_MS.toString(),
            "retireTimeoutMillis" to InferenceProcessProtocol.REAP_MS.toString(),
            "maxInputUtf8Bytes" to InferencePayloadLimits.MAX_INPUT_BYTES.toString(),
            "maxFinalCharacters" to InferencePayloadLimits.MAX_FINAL_CHARACTERS.toString(),
            "maxSourceFragments" to ProposalProtocol.MAX_SOURCE_FRAGMENTS.toString(),
            "concurrency" to "1", "overflowPolicy" to "reject-no-queue",
        ),
    )
}
