package br.com.exames.inferencecloud

import br.com.exames.domain.inference.GenerationRequest
import br.com.exames.domain.inference.GenerationResult
import br.com.exames.domain.inference.InferenceFailure
import br.com.exames.domain.inference.InferencePayloadLimits
import br.com.exames.domain.inference.TextInference
import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.domain.validation.InferenceConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class CloudFallbackConfig(
    val endpointUrl: String,
    val authTokenProvider: suspend () -> String? = { null },
    val connectTimeoutMillis: Long = 15_000L,
    val readTimeoutMillis: Long = 45_000L,
    val callTimeoutMillis: Long = 60_000L,
    val allowCleartextForLocalTests: Boolean = false,
) {
    val isConfigured: Boolean
        get() = endpointUrl.isNotBlank() && (allowCleartextForLocalTests || endpointUrl.startsWith("https://"))
}

@Serializable
internal data class CloudVisionRequestDto(
    val promptVersion: String,
    val schemaVersion: String,
    val instruction: String,
    val documentText: String,
    val imageMimeType: String,
    val imageBase64: String,
)

@Serializable
internal data class CloudVisionResponseDto(
    val text: String = "",
    val latencyMs: Long = 0L,
)

/**
 * Stateless HTTPS client for the opt-in private AWS Bedrock (google.gemma-4-e2b) fallback proxy.
 * Uses zero HTTP disk cache, strict TLS, cooperative cancellation, and zero payload logging.
 */
class CloudFallbackInference(
    private val config: CloudFallbackConfig,
) : TextInference {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(config.callTimeoutMillis, TimeUnit.MILLISECONDS)
        .apply {
            if (!config.allowCleartextForLocalTests) {
                connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            }
        }
        .build()

    override fun configuration(promptVersion: String, schemaVersion: String): InferenceConfiguration? {
        if (!config.isConfigured) return null
        return InferenceConfiguration(
            modelId = MODEL_ID,
            modelRevision = "bedrock-managed",
            modelSha256 = "cloud-managed-bedrock-gemma-4-e2b",
            quantization = "cloud-managed",
            runtimeVersion = "aws-bedrock-converse/1",
            promptVersion = promptVersion,
            schemaVersion = schemaVersion,
            backend = "CLOUD_BEDROCK",
            maxContextTokens = 4096,
            maxGenerationTokens = 1536,
            thinkingBudget = null,
            timeoutMillis = config.callTimeoutMillis,
            generationParameters = mapOf(
                "temperature" to "0.0",
                "stateless" to "true",
                "modelInvocationLogging" to "disabled",
                "httpDiskCache" to "disabled",
            ),
        )
    }

    override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
        if (mode == ExtractionMode.BASELINE) return GenerationResult.NotRequested
        if (!config.isConfigured) return GenerationResult.Failed(InferenceFailure.CLOUD_UNAVAILABLE)
        if (!InferencePayloadLimits.accepts(request)) return GenerationResult.Failed(InferenceFailure.INPUT_LIMIT)
        val imageBytes = request.imageBytes
        val imageMime = request.imageMimeType
        if (imageBytes == null || imageBytes.isEmpty() || imageMime.isNullOrBlank()) {
            return GenerationResult.Failed(InferenceFailure.INPUT_LIMIT)
        }

        val dto = CloudVisionRequestDto(
            promptVersion = request.promptVersion,
            schemaVersion = request.schemaVersion,
            instruction = request.instruction,
            documentText = request.documentText,
            imageMimeType = imageMime,
            imageBase64 = Base64.getEncoder().encodeToString(imageBytes),
        )
        val payload = json.encodeToString(CloudVisionRequestDto.serializer(), dto)
        val token = try {
            config.authTokenProvider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return GenerationResult.Failed(InferenceFailure.CLOUD_UNAUTHORIZED)
        }

        val httpRequest = Request.Builder()
            .url(config.endpointUrl)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .apply {
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val startNanos = System.nanoTime()
        return executeCancellable(httpRequest, startNanos)
    }

    private suspend fun executeCancellable(
        httpRequest: Request,
        startNanos: Long,
    ): GenerationResult = suspendCancellableCoroutine { cont ->
        val call = client.newCall(httpRequest)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled || call.isCanceled()) return
                val failure = when (e) {
                    is SocketTimeoutException -> InferenceFailure.GENERATION_TIMEOUT
                    else -> InferenceFailure.NETWORK_UNAVAILABLE
                }
                cont.resume(GenerationResult.Failed(failure))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (cont.isCancelled) return
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startNanos).coerceAtLeast(0L))
                    if (!res.isSuccessful) {
                        val failure = mapHttpFailure(res.code)
                        cont.resume(GenerationResult.Failed(failure))
                        return
                    }
                    val bodyString = try {
                        res.body?.string().orEmpty()
                    } catch (_: IOException) {
                        cont.resume(GenerationResult.Failed(InferenceFailure.NETWORK_UNAVAILABLE))
                        return
                    }
                    val parsed = try {
                        json.decodeFromString(CloudVisionResponseDto.serializer(), bodyString)
                    } catch (_: Exception) {
                        cont.resume(GenerationResult.Failed(InferenceFailure.CLOUD_UNAVAILABLE))
                        return
                    }
                    val text = parsed.text.trim()
                    when {
                        text.isEmpty() -> cont.resume(GenerationResult.Failed(InferenceFailure.EMPTY_FINAL))
                        text.length > InferencePayloadLimits.MAX_FINAL_CHARACTERS ->
                            cont.resume(GenerationResult.Failed(InferenceFailure.OUTPUT_LIMIT))
                        else -> cont.resume(
                            GenerationResult.UnvalidatedFinal(
                                text = text,
                                loadMillis = 0L,
                                generationMillis = if (parsed.latencyMs > 0L) parsed.latencyMs else elapsedMs,
                            ),
                        )
                    }
                }
            }
        })
    }

    private fun mapHttpFailure(code: Int): InferenceFailure = when (code) {
        400, 413 -> InferenceFailure.INPUT_LIMIT
        401, 403 -> InferenceFailure.CLOUD_UNAUTHORIZED
        429 -> InferenceFailure.CLOUD_RATE_LIMITED
        504 -> InferenceFailure.GENERATION_TIMEOUT
        else -> InferenceFailure.CLOUD_UNAVAILABLE
    }

    companion object {
        const val MODEL_ID = "google.gemma-4-e2b"
    }
}
