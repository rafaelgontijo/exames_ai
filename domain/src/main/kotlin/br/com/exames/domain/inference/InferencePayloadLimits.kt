package br.com.exames.domain.inference

/** IPC/memory bounds, not a tokenizer estimate. Native context/generation limits remain mandatory. */
object InferencePayloadLimits {
    const val MAX_INPUT_BYTES = 16384
    const val MAX_IMAGE_BYTES = 512 * 1024
    const val MAX_FINAL_CHARACTERS = 16384
    val ALLOWED_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg")

    fun accepts(request: GenerationRequest): Boolean {
        if (request.instruction.isBlank() || request.documentText.isBlank() ||
            request.promptVersion.isBlank() || request.schemaVersion.isBlank() ||
            request.promptVersion.length > 128 || request.schemaVersion.length > 128) return false
        // Bound character allocation before encoding arbitrary caller input.
        if (request.instruction.length > MAX_INPUT_BYTES || request.documentText.length > MAX_INPUT_BYTES) return false
        val textBytes = request.instruction.toByteArray(Charsets.UTF_8).size +
            request.documentText.toByteArray(Charsets.UTF_8).size
        if (textBytes > MAX_INPUT_BYTES) return false

        val bytes = request.imageBytes
        val mime = request.imageMimeType
        if ((bytes == null) != (mime == null)) return false
        if (bytes != null && mime != null) {
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES || mime !in ALLOWED_IMAGE_MIME_TYPES) return false
        }
        return true
    }
}
