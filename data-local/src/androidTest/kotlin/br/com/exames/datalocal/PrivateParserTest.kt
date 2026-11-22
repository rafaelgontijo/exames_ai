package br.com.exames.datalocal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.datadocuments.PageTextExtractor
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.AssessmentStatus
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.parser.SupportedLayout
import br.com.exames.domain.inference.*
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.domain.validation.InferenceOutcome
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateParserTest {
    @Test fun untrustedSimulatedProposalIsValidatedBeforeAssessmentOfRealPdfText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val renderer = PdfPreviewRenderer(context) { SyntheticPdf.biochemistry(false) }
        renderer.open("synthetic").use { session -> session.readPage(1).use { page ->
            val fragments = PageTextExtractor().fromPdf(page).fragments
            val backend = object : TextInference {
                override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult =
                    GenerationResult.UnvalidatedFinal("""{"schema":"biochemistry-fields/1","observations":[{"analyte":{"raw":"Colesterol","spans":["e1"]},"result":null,"unit":null,"references":[],"context":null}]}""", 0, 0)
            }
            val processor = ProcessBiochemistryPage(backend)
            val baseline = processor.process("synthetic", "baseline", 1, fragments, ExtractionMode.BASELINE)
            assertEquals(5, baseline.assessments.size)
            assertTrue(baseline.assessments.all { it.status == AssessmentStatus.WITHIN_RANGE })
            val attempted = processor.process("synthetic", "assisted", 1, fragments, ExtractionMode.ASSISTED)
            assertEquals(5, attempted.assessments.size)
            assertTrue(attempted.assessments.all { it.status == AssessmentStatus.NEEDS_REVIEW })
            assertEquals(InferenceOutcome.FALLBACK, attempted.inference.outcome)
            assertTrue(ModelEvidenceIssue.UNSUPPORTED_ANALYTE in attempted.inference.issues)
            assertTrue(attempted.parsed.observations.all { !it.observation.confirmed && it.observation.resultRaw == "15" })
        } }
    }

    @Test fun bothPdfLayoutsReachParserWithGlyphEvidenceAndNoExpectedAnswers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (footnote in listOf(false, true)) {
            val renderer = PdfPreviewRenderer(context) { SyntheticPdf.biochemistry(footnote) }
            renderer.open("synthetic").use { session ->
                session.readPage(1).use { page ->
                    val source = PageTextExtractor().fromPdf(page)
                    val parsed = BiochemistryParser().parse("synthetic", "run-$footnote", 1, source.fragments)
                    assertEquals(if (footnote) SupportedLayout.FOOTNOTE else SupportedLayout.SIMPLE, parsed.layout)
                    assertEquals(5, parsed.observations.size)
                    assertTrue(parsed.warnings.isEmpty())
                    for (output in parsed.observations) {
                        val observation = output.observation
                        assertEquals("15", observation.resultRaw)
                        assertFalse(observation.confirmed)
                        assertEquals(AssessmentStatus.WITHIN_RANGE,
                            DeterministicRangeEvaluator().evaluate(observation, output.referenceForAssessment).status)
                        assertEquals(1, observation.referenceCandidates.size)
                        assertTrue(observation.fieldEvidence.isNotEmpty())
                        assertTrue(observation.fieldEvidence.all { evidence ->
                            source.fragments.any { it.spanId == evidence.spanId && it.raw == evidence.raw && it.region == evidence.region }
                        })
                        if (footnote) assertTrue(observation.fieldEvidence.any {
                            it.field == "referenceRaw" && it.region.top > page.bitmap.height * 0.65
                        })
                    }
                }
            }
        }
    }
}
