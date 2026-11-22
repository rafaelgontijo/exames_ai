package br.com.exames.domain.validation

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*
import br.com.exames.domain.inference.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class CompareExtractionModesTest {
    private val rule=DeterministicRangeEvaluator.RULE_VERSION
    private val expected=ExpectedObservation("expected",Analyte.GLUCOSE,"Glicose","25","mg/dL","10 a 20 mg/dL","25","10","20",
        ExpectedStatus.ABOVE_RANGE,"ABOVE_UPPER_BOUND",true,listOf(ExtractedFieldEvidence("analyteRaw","Glicose",SourceRegion(1,0.0,0.0,20.0,20.0,100.0,100.0),"span","synthetic","1")))
    private val truth=GroundTruthSnapshot("a".repeat(64),GroundTruthVersion(caseId="case",version="1",reviewState=ReviewState.REVIEWED,
        reviewerId="synthetic",reviewedAt=Instant.EPOCH.toString(),observations=listOf(expected)))
    private val case=CorpusCase("case","family",CorpusOrigin.SYNTHETIC,CorpusFormat.PNG,CorpusPartition.DEVELOPMENT,"simple","1",
        "documents/source.png","b".repeat(64),1,"truth/source.json",truth.sha256)
    private val b=ProcessingRun("base","doc",case.documentSha256,"case",ExtractionMode.BASELINE,"ext","parse",rule,RunState.COMPLETE,Instant.EPOCH.toString(),"device","36")
    private val a=b.copy(runId="assist",mode=ExtractionMode.ASSISTED,inferenceOutcome=InferenceOutcome.FALLBACK)
    private fun repo(assisted: ProcessingRun=a): ValidationRepository = mockk<ValidationRepository>().also { r ->
        coEvery { r.findRun("base") } returns b;coEvery { r.findRun("assist") } returns assisted
        coEvery { r.find("doc") } returns Document("doc","artifact:original",case.documentSha256,"image/png",Instant.EPOCH,1,corpusCase=case)
        coEvery { r.groundTruth("doc") } returns listOf(truth)
        for (run in listOf(b,assisted)) {
            val o=Observation(run.runId,"doc","Glicose",if(run.mode==ExtractionMode.BASELINE) "25" else "15","mg/dL",1,"synthetic",numericValue=(if(run.mode==ExtractionMode.BASELINE) "25" else "15").toBigDecimal())
            val ref=ReferenceRange(o.id,"10 a 20 mg/dL","mg/dL",1,"10".toBigDecimal(),"20".toBigDecimal(),applicability=Applicability.APPLICABLE)
            coEvery { r.observations(run.runId) } returns listOf(o)
            coEvery { r.reference(o.id) } returns ref
            coEvery { r.assessments(run.runId) } returns listOf(DeterministicRangeEvaluator().evaluate(o,ref))
            coEvery { r.comparisons(run.runId) } returns emptyList()
            coEvery { r.checkpoints(run.runId) } returns listOf(PageCheckpoint(run.runId,1,run.mode,PageState.COMPLETE,20,inferenceOutcome=run.inferenceOutcome))
        }
        coEvery { r.appendComparison(any()) } just Runs
    }
    @Test fun coverageDoesNotHideNewErrorsAndParserCompletionNeverCountsAsModelSuccess() = runBlocking {
        val r=repo();val report=CompareExtractionModes(r).compare("base","assist",truth.sha256)
        assertEquals(0,report.assisted.acceptedModelPages);assertEquals(1,report.assisted.fallbackOrFailedPages)
        assertEquals(listOf("expected"),report.newErrors);assertEquals(listOf("expected"),report.newAbnormalShownWithin)
        assertEquals(0L,report.numericCoverageDelta)
        assertEquals(1,report.divergences.size);assertTrue("raw.resultRaw" in report.divergences.single().fields)
        assertEquals(report,ValidationJson.format.decodeFromString<PairedValidationReport>(ValidationJson.format.encodeToString(report)))
        coVerify(exactly=0) { r.createRun(any());r.commitPage(any());r.appendCorrection(any()) }
    }
    @Test fun crossSourceRuleConfigurationAndCorrectedPairsAreRejectedBeforeMeasuring() = runBlocking {
        for (invalid in listOf(a.copy(documentId="other"),a.copy(ruleVersion="other"),a.copy(parserVersion="other"),
            a.copy(mode=ExtractionMode.BASELINE),a.copy(outputBasis=OutputBasis.CORRECTED,correctionIds=listOf("correction")),a.copy(state=RunState.RUNNING))) {
            val r=repo(invalid)
            try { CompareExtractionModes(r).compare("base","assist",truth.sha256);fail("Invalid pair accepted") } catch (_: IllegalArgumentException) { }
            coVerify(exactly=0) { r.appendComparison(any()) }
        }
    }
    @Test fun numericMeasurementsRejectNegativeValuesAndKeepUnavailableTokensDistinctFromZero() {
        val m=RuntimeMeasurements(10,100,1000,900,2000,20,30,50)
        assertNull(m.thoughtTokens);assertNull(m.finalTokens);assertEquals(30,m.decodedTokens)
        assertThrows(IllegalArgumentException::class.java) { m.copy(prefillTokens=-1) }
        assertThrows(IllegalArgumentException::class.java) { m.copy(elapsedMillis=-1) }
        assertThrows(IllegalArgumentException::class.java) { m.copy(memoryMethod="peak") }
        val page=PageMeasurements(1,2,3,4,5,6)
        assertEquals(page,ValidationJson.format.decodeFromString<PageMeasurements>(ValidationJson.format.encodeToString(page)))
        assertThrows(IllegalArgumentException::class.java) { page.copy(clock="wall-clock") }
        assertThrows(IllegalArgumentException::class.java) { page.copy(totalRamBytes=-1) }
    }
}
