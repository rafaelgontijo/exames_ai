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

    @Test fun boundsOptionalImagePayloadAndMimeType() {
        fun imgReq(bytes: ByteArray?, mime: String?) =
            GenerationRequest("instr", "doc", "exam-vision/1", "exam-vision-items/1", bytes, mime)

        assertTrue(InferencePayloadLimits.accepts(imgReq(ByteArray(1), "image/png")))
        assertTrue(InferencePayloadLimits.accepts(imgReq(ByteArray(InferencePayloadLimits.MAX_IMAGE_BYTES), "image/jpeg")))
        assertFalse(InferencePayloadLimits.accepts(imgReq(ByteArray(InferencePayloadLimits.MAX_IMAGE_BYTES + 1), "image/png")))
        assertFalse(InferencePayloadLimits.accepts(imgReq(ByteArray(0), "image/png")))
        assertFalse(InferencePayloadLimits.accepts(imgReq(ByteArray(16), null)))
        assertFalse(InferencePayloadLimits.accepts(imgReq(null, "image/png")))
        assertFalse(InferencePayloadLimits.accepts(imgReq(ByteArray(16), "image/webp")))
        assertFalse(InferencePayloadLimits.accepts(imgReq(ByteArray(16), "application/pdf")))
    }
}
