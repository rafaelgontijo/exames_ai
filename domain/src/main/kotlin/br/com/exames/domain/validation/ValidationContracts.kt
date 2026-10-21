package br.com.exames.domain.validation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Explicit wire contract shared by corpus packages and local history. No Android types. */
object ValidationJson {
    val format = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
}

object StableIds {
    /** Length framing avoids collisions between component boundaries. Contains no source text. */
    fun of(vararg components: String): String {
        require(components.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
        components.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

@Serializable enum class CorpusOrigin { SYNTHETIC, PUBLIC_DEMONSTRATION }
@Serializable enum class CorpusFormat { PDF_TEXT, PDF_SCANNED, PNG, JPEG }
@Serializable enum class CorpusPartition { DEVELOPMENT, HELD_OUT }
@Serializable enum class ReviewState { PENDING, REVIEWED }
@Serializable enum class Analyte { GLUCOSE, UREA, CREATININE, SODIUM, POTASSIUM }
@Serializable enum class ExpectedStatus { BELOW_RANGE, WITHIN_RANGE, ABOVE_RANGE, NOT_CLASSIFIABLE, NEEDS_REVIEW }
@Serializable enum class ExtractionMode { BASELINE, ASSISTED }
@Serializable enum class RunState { QUEUED, RUNNING, COMPLETE, PARTIAL, FAILED, CANCELLED }
@Serializable enum class PageState { PENDING, COMPLETE, FAILED, CANCELLED }
@Serializable enum class InferenceOutcome { NOT_ATTEMPTED, SUCCEEDED, FALLBACK, FAILED, CANCELLED }

@Serializable
data class PublicProvenance(
    val url: String,
    val obtainedOn: String,
    val reuseTerms: String,
    val eligibilityReviewed: Boolean,
    val containsIdentifiableData: Boolean,
)

@Serializable
data class CorpusCase(
    val caseId: String,
    val familyId: String,
    val origin: CorpusOrigin,
    val format: CorpusFormat,
    val partition: CorpusPartition,
    val layoutId: String,
    val layoutVersion: String,
    val documentPath: String,
    val documentSha256: String,
    val documentBytes: Long,
    val groundTruthPath: String,
    val groundTruthSha256: String,
    val transformations: List<String> = emptyList(),
    val provenance: PublicProvenance? = null,
)

@Serializable
data class CorpusManifest(
    val schemaVersion: String = "1.0",
    val corpusVersion: String,
    val generatorVersion: String,
    val seed: Long,
    val fontSha256: String,
    val cases: List<CorpusCase>,
)

@Serializable
data class SourceRegion(
    val page: Int,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val pageWidth: Double,
    val pageHeight: Double,
    val rotationDegrees: Int = 0,
) {
    init {
        require(page >= 1 && rotationDegrees in setOf(0, 90, 180, 270))
        require(listOf(left, top, right, bottom, pageWidth, pageHeight).all { it.isFinite() })
        require(pageWidth > 0 && pageHeight > 0 && left >= 0 && top >= 0)
        require(right > left && bottom > top && right <= pageWidth && bottom <= pageHeight)
    }
}

@Serializable
data class ExtractedFieldEvidence(
    val field: String,
    val raw: String,
    val region: SourceRegion,
    val spanId: String,
    val method: String,
    val extractorVersion: String,
    val confidence: Double? = null,
) {
    init { require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0) }
}

@Serializable
data class ExpectedObservation(
    val observationId: String,
    val analyte: Analyte,
    val analyteRaw: String,
    val resultRaw: String,
    val unitRaw: String?,
    val referenceRaw: String?,
    /** Decimal strings avoid floating-point changes in JSON or expected answers. */
    val numericValue: String?,
    val low: String?,
    val high: String?,
    val expectedStatus: ExpectedStatus,
    val expectedReason: String,
    val recoverable: Boolean,
    val evidence: List<ExtractedFieldEvidence>,
)

@Serializable
data class GroundTruthVersion(
    val schemaVersion: String = "1.0",
    val caseId: String,
    val version: String,
    val reviewState: ReviewState,
    val reviewerId: String?,
    val reviewedAt: String?,
    val observations: List<ExpectedObservation>,
)

@Serializable
data class InferenceConfiguration(
    val modelId: String,
    val modelRevision: String,
    val modelSha256: String,
    val quantization: String,
    val runtimeVersion: String,
    val promptVersion: String,
    val schemaVersion: String,
    val backend: String,
    val maxContextTokens: Int,
    val maxGenerationTokens: Int,
    val thinkingBudget: Int?,
    val timeoutMillis: Long,
    val generationParameters: Map<String, String>,
)

@Serializable
data class ProcessingRun(
    val runId: String,
    val documentId: String,
    val documentSha256: String,
    val caseId: String?,
    val mode: ExtractionMode,
    val extractorVersion: String,
    val parserVersion: String,
    val ruleVersion: String,
    val state: RunState,
    val startedAt: String,
    val deviceModel: String,
    val androidVersion: String,
    val inference: InferenceConfiguration? = null,
    val inferenceOutcome: InferenceOutcome = InferenceOutcome.NOT_ATTEMPTED,
    val parentRunId: String? = null,
)

@Serializable
data class PageCheckpoint(
    val runId: String,
    val page: Int,
    val mode: ExtractionMode,
    val state: PageState,
    val durationMillis: Long?,
    val errorCode: String? = null,
)

@Serializable
data class FieldDifference(val observationId: String, val field: String, val expected: String?, val actual: String?)

@Serializable
data class Comparison(
    val comparisonId: String,
    val runId: String,
    val caseId: String,
    val groundTruthVersion: String,
    val groundTruthSha256: String,
    val comparedAt: String,
    val differences: List<FieldDifference>,
    val missingObservationIds: List<String>,
    val extraObservationIds: List<String>,
)
