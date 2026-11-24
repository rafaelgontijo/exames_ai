package br.com.exames.app

import br.com.exames.domain.inference.CloudFallbackPolicy
import br.com.exames.domain.inference.DeviceCapabilitySnapshot
import br.com.exames.domain.inference.DeviceCapabilityThresholds
import br.com.exames.domain.inference.GenerationRequest
import br.com.exames.domain.inference.GenerationResult
import br.com.exames.domain.inference.InferenceFailure
import br.com.exames.domain.inference.InferenceRoutingDecisionEngine
import br.com.exames.domain.inference.LocalWeightsState
import br.com.exames.domain.inference.TextInference
import br.com.exames.domain.validation.ExtractionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HybridTextInferenceTest {
    private val sampleReq = GenerationRequest("inst", "doc", "exam-vision/1", "exam-vision-items/1")
    private val capable = DeviceCapabilitySnapshot(
        totalRamBytes = DeviceCapabilityThresholds.MIN_TOTAL_RAM_BYTES,
        availableRamBytes = DeviceCapabilityThresholds.MIN_AVAILABLE_RAM_BYTES,
        is64Bit = true,
        hasSupportedGpu = true,
    )

    @Test fun dispatchesLocalNeverCallingCloudWhenReady() = runBlocking {
        var localCalls = 0
        var cloudCalls = 0
        val local = object : TextInference {
            override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
                localCalls++
                return GenerationResult.UnvalidatedFinal("local", 1, 1)
            }
        }
        val cloud = object : TextInference {
            override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
                cloudCalls++
                return GenerationResult.UnvalidatedFinal("cloud", 1, 1)
            }
        }
        val decision = InferenceRoutingDecisionEngine.decide(
            capable,
            LocalWeightsState.READY,
            CloudFallbackPolicy(cloudFallbackOptIn = true),
        )
        val hybrid = HybridTextInference(decision, local, cloud)
        val out = hybrid.generate(ExtractionMode.ASSISTED, sampleReq) as GenerationResult.UnvalidatedFinal
        assertEquals("local", out.text)
        assertEquals(1, localCalls)
        assertEquals(0, cloudCalls)
    }

    @Test fun blocksWithoutCallingCloudWhenOptInConsentNotGranted() = runBlocking {
        var cloudCalls = 0
        val noop = object : TextInference {
            override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
                cloudCalls++
                return GenerationResult.UnvalidatedFinal("cloud", 1, 1)
            }
        }
        val decision = InferenceRoutingDecisionEngine.decide(
            capable,
            LocalWeightsState.NOT_DOWNLOADED,
            CloudFallbackPolicy(cloudFallbackOptIn = false),
        )
        val hybrid = HybridTextInference(decision, noop, noop)
        assertEquals(
            GenerationResult.Failed(InferenceFailure.CLOUD_CONSENT_REQUIRED),
            hybrid.generate(ExtractionMode.ASSISTED, sampleReq),
        )
        assertEquals(0, cloudCalls)
    }

    @Test fun dispatchesCloudWhenOptInAuthorizedAndWeightsUnavailable() = runBlocking {
        var localCalls = 0
        var cloudCalls = 0
        val local = object : TextInference {
            override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
                localCalls++
                return GenerationResult.UnvalidatedFinal("local", 1, 1)
            }
        }
        val cloud = object : TextInference {
            override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
                cloudCalls++
                return GenerationResult.UnvalidatedFinal("cloud", 1, 1)
            }
        }
        val decision = InferenceRoutingDecisionEngine.decide(
            capable.copy(totalRamBytes = 4L * DeviceCapabilityThresholds.GIB),
            LocalWeightsState.NOT_DOWNLOADED,
            CloudFallbackPolicy(cloudFallbackOptIn = true, networkAvailable = true),
        )
        val hybrid = HybridTextInference(decision, local, cloud)
        val out = hybrid.generate(ExtractionMode.ASSISTED, sampleReq) as GenerationResult.UnvalidatedFinal
        assertEquals("cloud", out.text)
        assertEquals(0, localCalls)
        assertEquals(1, cloudCalls)
    }
}
