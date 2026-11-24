package br.com.exames.inferencecloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * In-memory Bearer token provider for the opt-in private cloud fallback.
 * Supports either a static short-lived Bearer token or automatic in-memory refresh via
 * Amazon Cognito REFRESH_TOKEN_AUTH (zero disk cache, zero logging, strict TLS).
 */
class CloudAuthTokenProvider(
    private val staticBearerToken: String = "",
    private val cognitoRegion: String = "",
    private val cognitoClientId: String = "",
    private val cognitoRefreshToken: String = "",
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val amzJsonMediaType = "application/x-amz-json-1.1".toMediaType()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(null)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .build()
    }

    @Volatile private var cachedToken: String? = staticBearerToken.trim().takeIf { it.isNotEmpty() }
    @Volatile private var expiresAtEpochSeconds: Long = 0L

    private val canRefresh: Boolean
        get() = cognitoRegion.isNotBlank() && cognitoClientId.isNotBlank() && cognitoRefreshToken.isNotBlank()

    suspend fun getToken(): String? {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val current = cachedToken
        if (current != null && (!canRefresh || nowSeconds < expiresAtEpochSeconds - REFRESH_MARGIN_SECONDS)) {
            return current
        }
        if (!canRefresh) return current
        return mutex.withLock {
            val recheckNow = System.currentTimeMillis() / 1000L
            val recheck = cachedToken
            if (recheck != null && recheckNow < expiresAtEpochSeconds - REFRESH_MARGIN_SECONDS) {
                return@withLock recheck
            }
            refreshFromCognito()
        }
    }

    private suspend fun refreshFromCognito(): String? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            CognitoRefreshRequest.serializer(),
            CognitoRefreshRequest(
                AuthFlow = "REFRESH_TOKEN_AUTH",
                ClientId = cognitoClientId,
                AuthParameters = mapOf("REFRESH_TOKEN" to cognitoRefreshToken),
            ),
        )
        val req = Request.Builder()
            .url("https://cognito-idp.$cognitoRegion.amazonaws.com/")
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
            .header("Cache-Control", "no-store")
            .post(body.toRequestBody(amzJsonMediaType))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext cachedToken
            val raw = res.body?.string().orEmpty()
            val parsed = runCatching {
                json.decodeFromString(CognitoRefreshResponse.serializer(), raw)
            }.getOrNull() ?: return@withContext cachedToken
            val auth = parsed.AuthenticationResult ?: return@withContext cachedToken
            val token = auth.IdToken?.takeIf { it.isNotBlank() } ?: auth.AccessToken?.takeIf { it.isNotBlank() }
            if (token != null) {
                cachedToken = token
                val ttl = (auth.ExpiresIn ?: 3600L).coerceAtLeast(120L)
                expiresAtEpochSeconds = (System.currentTimeMillis() / 1000L) + ttl
            }
            cachedToken
        }
    }

    @Serializable
    private data class CognitoRefreshRequest(
        val AuthFlow: String,
        val ClientId: String,
        val AuthParameters: Map<String, String>,
    )

    @Serializable
    private data class CognitoRefreshResponse(
        val AuthenticationResult: CognitoAuthResult? = null,
    )

    @Serializable
    private data class CognitoAuthResult(
        val IdToken: String? = null,
        val AccessToken: String? = null,
        val ExpiresIn: Long? = null,
    )

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 60L
    }
}
