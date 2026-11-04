package br.com.exames.domain.inference

enum class ModelReadiness { NOT_PREPARED, PREPARING, READY, UNSUPPORTED_DEVICE, VERIFICATION_PENDING,
    MISSING, CORRUPT, INSUFFICIENT_SPACE, STORAGE_UNAVAILABLE }
fun interface PrepareLocalInference { suspend fun prepare(): ModelReadiness }
fun interface AssistedRuntimePolicy { fun isEnabled(): Boolean }
