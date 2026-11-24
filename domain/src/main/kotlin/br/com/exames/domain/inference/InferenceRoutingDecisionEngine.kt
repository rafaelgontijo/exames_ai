package br.com.exames.domain.inference

/** Pure JVM snapshot of device hardware capabilities for local Gemma 4 E2B execution. */
data class DeviceCapabilitySnapshot(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val is64Bit: Boolean,
    val hasSupportedGpu: Boolean,
)

/** Current status of the pinned local Gemma 4 E2B weights on private device storage. */
enum class LocalWeightsState {
    READY,
    DOWNLOADING,
    NOT_DOWNLOADED,
    CORRUPT,
    INSUFFICIENT_SPACE,
}

/** User opt-in consent and runtime availability for private cloud fallback. */
data class CloudFallbackPolicy(
    val cloudFallbackOptIn: Boolean,
    val cloudEndpointConfigured: Boolean = true,
    val networkAvailable: Boolean = true,
)

enum class InferenceExecutionTarget {
    LOCAL,
    CLOUD_FALLBACK,
    BLOCKED_CONSENT_REQUIRED,
    BLOCKED_OFFLINE,
}

enum class RoutingReason {
    LOCAL_READY,
    HARDWARE_NOT_64BIT,
    HARDWARE_NO_GPU,
    HARDWARE_RAM_BELOW_MINIMUM,
    HARDWARE_AVAILABLE_RAM_LOW,
    WEIGHTS_DOWNLOADING,
    WEIGHTS_NOT_DOWNLOADED,
    WEIGHTS_CORRUPT,
    WEIGHTS_INSUFFICIENT_SPACE,
    CLOUD_ENDPOINT_UNCONFIGURED,
    CLOUD_NETWORK_UNAVAILABLE,
}

data class RoutingDecision(
    val target: InferenceExecutionTarget,
    val reason: RoutingReason,
    val hardwareEligibleForLocal: Boolean,
    val canDownloadLocal: Boolean,
)

object DeviceCapabilityThresholds {
    const val GIB: Long = 1024L * 1024L * 1024L
    const val MIN_TOTAL_RAM_BYTES: Long = 6L * GIB
    const val MIN_AVAILABLE_RAM_BYTES: Long = (2560L) * 1024L * 1024L // 2.5 GiB
}

/**
 * Pure deterministic Local-First decision engine.
 * Always prioritizes on-device inference when hardware and verified weights allow;
 * never routes to cloud without explicit opt-in consent.
 */
object InferenceRoutingDecisionEngine {
    fun decide(
        device: DeviceCapabilitySnapshot,
        weights: LocalWeightsState,
        policy: CloudFallbackPolicy,
    ): RoutingDecision {
        val hardwareBlockReason = when {
            !device.is64Bit -> RoutingReason.HARDWARE_NOT_64BIT
            !device.hasSupportedGpu -> RoutingReason.HARDWARE_NO_GPU
            device.totalRamBytes < DeviceCapabilityThresholds.MIN_TOTAL_RAM_BYTES -> RoutingReason.HARDWARE_RAM_BELOW_MINIMUM
            device.availableRamBytes < DeviceCapabilityThresholds.MIN_AVAILABLE_RAM_BYTES -> RoutingReason.HARDWARE_AVAILABLE_RAM_LOW
            else -> null
        }
        val hardwareEligible = hardwareBlockReason == null
        val canDownloadLocal = device.is64Bit &&
            device.hasSupportedGpu &&
            device.totalRamBytes >= DeviceCapabilityThresholds.MIN_TOTAL_RAM_BYTES &&
            weights != LocalWeightsState.INSUFFICIENT_SPACE

        if (hardwareEligible && weights == LocalWeightsState.READY) {
            return RoutingDecision(
                target = InferenceExecutionTarget.LOCAL,
                reason = RoutingReason.LOCAL_READY,
                hardwareEligibleForLocal = true,
                canDownloadLocal = true,
            )
        }

        val fallbackTriggerReason = hardwareBlockReason ?: when (weights) {
            LocalWeightsState.READY -> RoutingReason.LOCAL_READY
            LocalWeightsState.DOWNLOADING -> RoutingReason.WEIGHTS_DOWNLOADING
            LocalWeightsState.NOT_DOWNLOADED -> RoutingReason.WEIGHTS_NOT_DOWNLOADED
            LocalWeightsState.CORRUPT -> RoutingReason.WEIGHTS_CORRUPT
            LocalWeightsState.INSUFFICIENT_SPACE -> RoutingReason.WEIGHTS_INSUFFICIENT_SPACE
        }

        if (!policy.cloudEndpointConfigured) {
            return RoutingDecision(
                target = InferenceExecutionTarget.BLOCKED_OFFLINE,
                reason = if (hardwareBlockReason != null) fallbackTriggerReason else RoutingReason.CLOUD_ENDPOINT_UNCONFIGURED,
                hardwareEligibleForLocal = hardwareEligible,
                canDownloadLocal = canDownloadLocal,
            )
        }

        if (!policy.cloudFallbackOptIn) {
            return RoutingDecision(
                target = InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED,
                reason = fallbackTriggerReason,
                hardwareEligibleForLocal = hardwareEligible,
                canDownloadLocal = canDownloadLocal,
            )
        }

        if (!policy.networkAvailable) {
            return RoutingDecision(
                target = InferenceExecutionTarget.BLOCKED_OFFLINE,
                reason = RoutingReason.CLOUD_NETWORK_UNAVAILABLE,
                hardwareEligibleForLocal = hardwareEligible,
                canDownloadLocal = canDownloadLocal,
            )
        }

        return RoutingDecision(
            target = InferenceExecutionTarget.CLOUD_FALLBACK,
            reason = fallbackTriggerReason,
            hardwareEligibleForLocal = hardwareEligible,
            canDownloadLocal = canDownloadLocal,
        )
    }
}
