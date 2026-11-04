package br.com.exames.domain.inference

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InferencePayloadLimitsTest {
    private fun request(instruction: String = "x", document: String = "y", prompt: String = "test/1", schema: String = "test/1") =
        GenerationRequest(instruction, document, prompt, schema)
    @Test fun boundsCombinedUtf8BytesWithoutSilentlyTruncatingInput() {
        assertTrue(InferencePayloadLimits.accepts(request(document = "x".repeat(16383))))
        assertFalse(InferencePayloadLimits.accepts(request(document = "x".repeat(16384))))
        assertTrue(InferencePayloadLimits.accepts(request(instruction = "xx", document = "á".repeat(8191))))
        assertFalse(InferencePayloadLimits.accepts(request(instruction = "xxx", document = "á".repeat(8191))))
        assertFalse(InferencePayloadLimits.accepts(request(document = "x".repeat(100000))))
    }
    @Test fun rejectsBlankFieldsAndBoundsVersionMetadata() {
        for (value in listOf(request(instruction = " "), request(document = ""), request(prompt = ""), request(schema = " "),
            request(prompt = "x".repeat(129)), request(schema = "x".repeat(129)))) assertFalse(InferencePayloadLimits.accepts(value))
        assertTrue(InferencePayloadLimits.accepts(request(prompt = "x".repeat(128), schema = "x".repeat(128))))
    }
}
