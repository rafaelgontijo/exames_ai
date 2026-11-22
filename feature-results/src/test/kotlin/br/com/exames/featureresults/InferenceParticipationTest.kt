package br.com.exames.featureresults

import br.com.exames.domain.inference.*
import br.com.exames.domain.validation.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class InferenceParticipationTest {
    private val config=InferenceConfiguration("model","revision","a".repeat(64),"int4","runtime","prompt","schema","GPU",2048,1024,null,1000,emptyMap())
    private val run=ProcessingRun("run","doc","a".repeat(64),null,ExtractionMode.ASSISTED,"ext","parse","rule",RunState.COMPLETE,Instant.EPOCH.toString(),"device","36",inference=config,inferenceOutcome=InferenceOutcome.SUCCEEDED)
    @Test fun legacySuccessWithoutAnAttestedAttemptCannotClaimActualModelParticipation() {
        val page=PageCheckpoint("run",1,run.mode,PageState.COMPLETE,1,inferenceOutcome=InferenceOutcome.SUCCEEDED)
        assertFalse(inferenceParticipation(run,listOf(page)).startsWith("IA executada"))
        assertTrue(inferenceParticipation(run,listOf(page)).contains("desconhecida"))
    }
    @Test fun freshAcceptedEvidenceIsDistinctFromFallbackAndReassessment() {
        val attempt=PageInferenceAttempt(ProposalDisposition.ACCEPTED,InferenceOutcome.SUCCEEDED,requestSubmitted=true,configuration=config)
        val page=PageCheckpoint("run",1,run.mode,PageState.COMPLETE,1,inferenceOutcome=InferenceOutcome.SUCCEEDED,inferenceAttempt=attempt)
        assertTrue(inferenceParticipation(run,listOf(page)).startsWith("IA executada"))
        assertTrue(inferenceParticipation(run.copy(outputBasis=OutputBasis.REASSESSED),listOf(page)).contains("não executou"))
        assertTrue(inferenceParticipation(run.copy(inferenceOutcome=InferenceOutcome.FALLBACK),listOf(page.copy(inferenceOutcome=InferenceOutcome.FALLBACK))).contains("Fallback/falha: 1"))
    }
}
