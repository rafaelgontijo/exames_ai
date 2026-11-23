package br.com.exames.domain.extraction

/** Ephemeral biological sex for in-memory stratified reference selection; never persisted. */
enum class BiologicalSex {
    UNSPECIFIED,
    FEMALE,
    MALE,
}

/** Ephemeral fasting status for in-memory stratified reference selection; never persisted. */
enum class FastingState {
    UNSPECIFIED,
    FASTING,
    NON_FASTING,
}

/** Ephemeral pregnancy status for in-memory stratified reference selection; never persisted. */
enum class PregnancyState {
    UNSPECIFIED,
    NOT_PREGNANT,
    PREGNANT,
}

/**
 * Ephemeral, session-only basic clinical context used strictly in memory to select an explicitly
 * printed reference stratum from the exam document. Never persisted or logged.
 */
data class ClinicalContext(
    val sex: BiologicalSex = BiologicalSex.UNSPECIFIED,
    val ageYears: Int? = null,
    val fasting: FastingState = FastingState.UNSPECIFIED,
    val pregnancy: PregnancyState = PregnancyState.UNSPECIFIED,
) {
    init {
        require(ageYears == null || ageYears in 0..120) { "AGE_OUT_OF_RANGE" }
    }

    val isEmpty: Boolean
        get() = sex == BiologicalSex.UNSPECIFIED &&
            ageYears == null &&
            fasting == FastingState.UNSPECIFIED &&
            pregnancy == PregnancyState.UNSPECIFIED
}
