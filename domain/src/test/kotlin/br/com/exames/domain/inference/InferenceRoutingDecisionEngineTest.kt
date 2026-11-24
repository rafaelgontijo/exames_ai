package br.com.exames.domain.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InferenceRoutingDecisionEngineTest {
    private val capableDevice = DeviceCapabilitySnapshot(
        totalRamBytes = DeviceCapabilityThresholds.MIN_TOTAL_RAM_BYTES,
        availableRamBytes = DeviceCapabilityThresholds.MIN_AVAILABLE_RAM_BYTES,
        is64Bit = true,
        hasSupportedGpu = true,
    )

    @Test fun selectsLocalStrictlyWhenCapableHardwareAndVerifiedWeightsAreReady() {
        val decision = InferenceRoutingDecisionEngine.decide(
            device = capableDevice,
            weights = LocalWeightsState.READY,
            policy = CloudFallbackPolicy(cloudFallbackOptIn = true, networkAvailable = false),
        )
        assertEquals(InferenceExecutionTarget.LOCAL, decision.target)
        assertEquals(RoutingReason.LOCAL_READY, decision.reason)
        assertTrue(decision.hardwareEligibleForLocal)
        assertTrue(decision.canDownloadLocal)
    }

    @Test fun blocksForExplicitOptInWhenHardwareBelowRamOrGpuThresholds() {
        val lowTotalRam = capableDevice.copy(totalRamBytes = DeviceCapabilityThresholds.MIN_TOTAL_RAM_BYTES - 1)
        val d1 = InferenceRoutingDecisionEngine.decide(lowTotalRam, LocalWeightsState.READY, CloudFallbackPolicy(cloudFallbackOptIn = false))
        assertEquals(InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED, d1.target)
        assertEquals(RoutingReason.HARDWARE_RAM_BELOW_MINIMUM, d1.reason)
        assertFalse(d1.hardwareEligibleForLocal)
        assertFalse(d1.canDownloadLocal)

        val lowAvailRam = capableDevice.copy(availableRamBytes = DeviceCapabilityThresholds.MIN_AVAILABLE_RAM_BYTES - 1)
        val d2 = InferenceRoutingDecisionEngine.decide(lowAvailRam, LocalWeightsState.READY, CloudFallbackPolicy(cloudFallbackOptIn = false))
        assertEquals(InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED, d2.target)
        assertEquals(RoutingReason.HARDWARE_AVAILABLE_RAM_LOW, d2.reason)
        assertFalse(d2.hardwareEligibleForLocal)
        assertTrue(d2.canDownloadLocal)

        val noGpu = capableDevice.copy(hasSupportedGpu = false)
        val d3 = InferenceRoutingDecisionEngine.decide(noGpu, LocalWeightsState.READY, CloudFallbackPolicy(cloudFallbackOptIn = false))
        assertEquals(InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED, d3.target)
        assertEquals(RoutingReason.HARDWARE_NO_GPU, d3.reason)

        val bit32 = capableDevice.copy(is64Bit = false)
        val d4 = InferenceRoutingDecisionEngine.decide(bit32, LocalWeightsState.READY, CloudFallbackPolicy(cloudFallbackOptIn = false))
        assertEquals(InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED, d4.target)
        assertEquals(RoutingReason.HARDWARE_NOT_64BIT, d4.reason)
    }

    @Test fun routesToCloudOnlyAfterExplicitOptInWhenWeightsAreDownloadingOrAbsent() {
        val withoutConsent = InferenceRoutingDecisionEngine.decide(
            device = capableDevice,
            weights = LocalWeightsState.DOWNLOADING,
            policy = CloudFallbackPolicy(cloudFallbackOptIn = false),
        )
        assertEquals(InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED, withoutConsent.target)
        assertEquals(RoutingReason.WEIGHTS_DOWNLOADING, withoutConsent.reason)
        assertTrue(withoutConsent.hardwareEligibleForLocal)
        assertTrue(withoutConsent.canDownloadLocal)

        val withConsent = InferenceRoutingDecisionEngine.decide(
            device = capableDevice,
            weights = LocalWeightsState.NOT_DOWNLOADED,
            policy = CloudFallbackPolicy(cloudFallbackOptIn = true, networkAvailable = true),
        )
        assertEquals(InferenceExecutionTarget.CLOUD_FALLBACK, withConsent.target)
        assertEquals(RoutingReason.WEIGHTS_NOT_DOWNLOADED, withConsent.reason)
    }

    @Test fun staysBlockedOfflineWhenEndpointUnconfiguredOrNetworkUnavailable() {
        val noEndpoint = InferenceRoutingDecisionEngine.decide(
            device = capableDevice,
            weights = LocalWeightsState.NOT_DOWNLOADED,
            policy = CloudFallbackPolicy(cloudFallbackOptIn = true, cloudEndpointConfigured = false),
        )
        assertEquals(InferenceExecutionTarget.BLOCKED_OFFLINE, noEndpoint.target)
        assertEquals(RoutingReason.CLOUD_ENDPOINT_UNCONFIGURED, noEndpoint.reason)

        val noNetwork = InferenceRoutingDecisionEngine.decide(
            device = capableDevice,
            weights = LocalWeightsState.CORRUPT,
            policy = CloudFallbackPolicy(cloudFallbackOptIn = true, networkAvailable = false),
        )
        assertEquals(InferenceExecutionTarget.BLOCKED_OFFLINE, noNetwork.target)
        assertEquals(RoutingReason.CLOUD_NETWORK_UNAVAILABLE, noNetwork.reason)

        val noSpace = InferenceRoutingDecisionEngine.decide(
            device = capableDevice,
            weights = LocalWeightsState.INSUFFICIENT_SPACE,
            policy = CloudFallbackPolicy(cloudFallbackOptIn = false),
        )
        assertEquals(InferenceExecutionTarget.BLOCKED_CONSENT_REQUIRED, noSpace.target)
        assertEquals(RoutingReason.WEIGHTS_INSUFFICIENT_SPACE, noSpace.reason)
        assertFalse(noSpace.canDownloadLocal)
    }
}
