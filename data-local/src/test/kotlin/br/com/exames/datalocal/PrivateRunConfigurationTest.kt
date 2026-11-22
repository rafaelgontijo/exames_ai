package br.com.exames.datalocal

import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.inference.*
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.usecase.ProcessBiochemistryPage
import br.com.exames.domain.validation.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class PrivateRunConfigurationTest {
    private val configuration = InferenceConfiguration("synthetic", "revision-1", "a".repeat(64), "synthetic", "runtime-1",
        ProposalProtocol.PROMPT_VERSION, ProposalProtocol.SCHEMA_VERSION, "synthetic", 2048, 1024, null, 120000,
        mapOf("freshProcessPerRequest" to "true"))
    private fun pages(attested: InferenceConfiguration?, gate: Boolean = true): PrivateRunPages {
        val backend = object : TextInference {
            override fun configuration(promptVersion: String, schemaVersion: String) = attested
            override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult = error("MODEL_MUST_NOT_RUN")
        }
        return PrivateRunPages(mockk<PrivatePageLoader>(), mockk<PdfPreviewRenderer>(), ProcessBiochemistryPage(backend),
            attempts = mockk<ValidationRepository>(), assistedCompatible = { gate })
    }
    private val run = ProcessingRun("run", "doc", "b".repeat(64), null, ExtractionMode.ASSISTED,
        PrivateRunPages.VERSION, BiochemistryParser.VERSION, DeterministicRangeEvaluator.RULE_VERSION,
        RunState.QUEUED, "2026-10-30T00:00:00Z", "synthetic", "36", configuration)

    @Test fun assistedSchedulingRequiresAttestationAndIndependentAcceptanceGate() {
        assertTrue(pages(configuration).supports(run))
        assertFalse(pages(null).supports(run))
        assertFalse(pages(configuration, gate = false).supports(run))
        assertTrue(pages(null).supports(run.copy(mode = ExtractionMode.BASELINE, inference = null)))
        assertFalse(pages(configuration).supports(run.copy(mode = ExtractionMode.BASELINE)))
    }

    @Test fun modelRuntimeProtocolAndPolicyUpdatesCannotResumeUnderAnOldIdentity() {
        val variants = listOf(configuration.copy(modelId = "other"), configuration.copy(modelRevision = "revision-2"),
            configuration.copy(modelSha256 = "c".repeat(64)), configuration.copy(quantization = "other"),
            configuration.copy(runtimeVersion = "runtime-2"), configuration.copy(promptVersion = "prompt-2"),
            configuration.copy(schemaVersion = "schema-2"), configuration.copy(backend = "other"),
            configuration.copy(maxContextTokens = 4096), configuration.copy(maxGenerationTokens = 512),
            configuration.copy(thinkingBudget = 10), configuration.copy(timeoutMillis = 60000),
            configuration.copy(generationParameters = configuration.generationParameters + ("freshProcessPerRequest" to "false")))
        for (changed in variants) {
            assertFalse(pages(changed).supports(run))
            assertFalse(pages(configuration).supports(run.copy(inference = changed)))
            assertTrue(pages(changed).supports(run.copy(inference = changed)))
        }
    }
}
