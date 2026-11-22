package br.com.exames.domain.validation

import kotlinx.serialization.Serializable

/** Numeric operational measurements only. Null means unavailable, never zero work or zero tokens. */
@Serializable
data class RuntimeMeasurements(val elapsedMillis: Long, val sampledMaxPssKiB: Long,
    val availableBytesBefore: Long, val availableBytesAfter: Long, val totalRamBytes: Long,
    val prefillTokens: Int? = null, val decodedTokens: Int? = null, val contextTokensAfter: Int? = null,
    val thoughtTokens: Int? = null, val finalTokens: Int? = null,
    val tokenChannelsUnavailable: String = "SDK_NO_CHANNEL_TOKEN_COUNTS",
    val memoryMethod: String = "ANDROID_DEBUG_PSS_100MS_SAMPLED_MAX_NOT_PEAK_GPU_ACCOUNTING_INCOMPLETE",
    val freshEngine: Boolean = true, val processId: Int? = null) {
    init {
        require(listOf(elapsedMillis,sampledMaxPssKiB,availableBytesBefore,availableBytesAfter,totalRamBytes).all { it>=0 })
        require(listOf(prefillTokens,decodedTokens,contextTokensAfter,thoughtTokens,finalTokens).all { it==null || it>=0 })
        require(processId==null || processId>0)
        require(tokenChannelsUnavailable=="SDK_NO_CHANNEL_TOKEN_COUNTS")
        require(memoryMethod=="ANDROID_DEBUG_PSS_100MS_SAMPLED_MAX_NOT_PEAK_GPU_ACCOUNTING_INCOMPLETE")
    }
}
@Serializable
data class PageMeasurements(val elapsedMillis: Long, val appPssBeforeKiB: Long, val appPssAfterKiB: Long,
    val availableBytesBefore: Long, val availableBytesAfter: Long, val totalRamBytes: Long,
    val memoryMethod: String = "ANDROID_DEBUG_PSS_ENDPOINTS_NOT_PEAK_SEPARATE_FROM_INFERENCE_CHILD",
    val clock: String = "ANDROID_ELAPSED_REALTIME_MONOTONIC") {
    init {
        require(listOf(elapsedMillis,appPssBeforeKiB,appPssAfterKiB,availableBytesBefore,availableBytesAfter,totalRamBytes).all { it>=0 })
        require(memoryMethod=="ANDROID_DEBUG_PSS_ENDPOINTS_NOT_PEAK_SEPARATE_FROM_INFERENCE_CHILD" && clock=="ANDROID_ELAPSED_REALTIME_MONOTONIC")
    }
}
