package br.com.exames.inferencecloud

import br.com.exames.domain.extraction.VisionExamProtocol
import br.com.exames.domain.inference.GenerationRequest
import br.com.exames.domain.inference.GenerationResult
import br.com.exames.domain.inference.InferenceFailure
import br.com.exames.domain.inference.InferencePayloadLimits
import br.com.exames.domain.validation.ExtractionMode
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CloudFallbackInferenceTest {
    private val samplePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun sampleRequest(bytes: ByteArray = samplePng) = GenerationRequest(
        instruction = "Extract items",
        documentText = "Extract all printed laboratory results visible in this page band.",
        promptVersion = VisionExamProtocol.PROMPT_VERSION,
        schemaVersion = VisionExamProtocol.SCHEMA_VERSION,
        imageBytes = bytes,
        imageMimeType = "image/png",
    )

    @Test fun sendsStrictVisionContractWithAuthAndNoStoreHeaderAndParsesFinalChannel() = runBlocking {
        val server = MockWebServer()
        val expectedJson = """{"schema":"exam-vision-items/1","items":[]}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text":"{\"schema\":\"exam-vision-items/1\",\"items\":[]}","latencyMs":312}"""),
        )
        server.start()
        try {
            val client = CloudFallbackInference(
                CloudFallbackConfig(
                    endpointUrl = server.url("/v1/vision/extract").toString(),
                    authTokenProvider = { "opaque-session-token" },
                    allowCleartextForLocalTests = true,
                ),
            )
            val result = client.generate(ExtractionMode.ASSISTED, sampleRequest())
            assertTrue(result is GenerationResult.UnvalidatedFinal)
            val final = result as GenerationResult.UnvalidatedFinal
            assertEquals(expectedJson, final.text)
            assertEquals(312L, final.generationMillis)

            val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", recorded.method)
            assertEquals("Bearer opaque-session-token", recorded.getHeader("Authorization"))
            assertEquals("no-store", recorded.getHeader("Cache-Control"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"promptVersion\":\"exam-vision/1\""))
            assertTrue(body.contains("\"schemaVersion\":\"exam-vision-items/1\""))
            assertTrue(body.contains("\"imageMimeType\":\"image/png\""))
        } finally {
            server.shutdown()
        }
    }

    @Test fun mapsHttpErrorCodesAndRejectsCleartextOrOversizedPayloads() = runBlocking {
        // Non-HTTPS rejected when cleartext is not allowed
        val cleartext = CloudFallbackInference(
            CloudFallbackConfig(endpointUrl = "http://example.com/v1/vision/extract", allowCleartextForLocalTests = false),
        )
        assertEquals(
            GenerationResult.Failed(InferenceFailure.CLOUD_UNAVAILABLE),
            cleartext.generate(ExtractionMode.ASSISTED, sampleRequest()),
        )

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(413))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(504))
        server.start()
        try {
            val client = CloudFallbackInference(
                CloudFallbackConfig(
                    endpointUrl = server.url("/v1/vision/extract").toString(),
                    allowCleartextForLocalTests = true,
                ),
            )
            // Oversized image fails locally without hitting server
            val oversized = ByteArray(InferencePayloadLimits.MAX_IMAGE_BYTES + 1)
            assertEquals(
                GenerationResult.Failed(InferenceFailure.INPUT_LIMIT),
                client.generate(ExtractionMode.ASSISTED, sampleRequest(oversized)),
            )
            assertEquals(0, server.requestCount)

            assertEquals(GenerationResult.Failed(InferenceFailure.CLOUD_UNAUTHORIZED), client.generate(ExtractionMode.ASSISTED, sampleRequest()))
            assertEquals(GenerationResult.Failed(InferenceFailure.CLOUD_RATE_LIMITED), client.generate(ExtractionMode.ASSISTED, sampleRequest()))
            assertEquals(GenerationResult.Failed(InferenceFailure.INPUT_LIMIT), client.generate(ExtractionMode.ASSISTED, sampleRequest()))
            assertEquals(GenerationResult.Failed(InferenceFailure.CLOUD_UNAVAILABLE), client.generate(ExtractionMode.ASSISTED, sampleRequest()))
            assertEquals(GenerationResult.Failed(InferenceFailure.GENERATION_TIMEOUT), client.generate(ExtractionMode.ASSISTED, sampleRequest()))
        } finally {
            server.shutdown()
        }
    }

    @Test fun cancelsInFlightHttpCallCooperatively() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBodyDelay(5, TimeUnit.SECONDS).setResponseCode(200).setBody("""{"text":"ok"}"""))
        server.start()
        try {
            val client = CloudFallbackInference(
                CloudFallbackConfig(
                    endpointUrl = server.url("/v1/vision/extract").toString(),
                    allowCleartextForLocalTests = true,
                ),
            )
            val job = async { client.generate(ExtractionMode.ASSISTED, sampleRequest()) }
            delay(100)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        } finally {
            server.shutdown()
        }
    }
}
