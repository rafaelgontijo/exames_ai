package br.com.exames.featureresults

import br.com.exames.coreui.ChipKind
import br.com.exames.domain.model.AssessmentStatus
import br.com.exames.domain.summary.SummaryCompleteness
import br.com.exames.domain.summary.SummaryWarning
import br.com.exames.domain.validation.OutputBasis
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ResultLabelsTest {
    private val forbidden = listOf("tudo normal", "está bom", "esta bom", "sem problemas", "saudável", "saudavel", "normal", "diagnóstic", "urgên")
    private fun allLabels(): List<String> =
        AssessmentStatus.entries.map(::statusLabel) + SummaryCompleteness.entries.map(::completenessLabel) +
            SummaryWarning.entries.map(::warningLabel) + OutputBasis.entries.map { basisLabel(it, 2) }

    @Test fun everyLabelAvoidsNormalityGuaranteesAndClinicalWords() {
        for (l in allLabels()) for (bad in forbidden) assertFalse("$l ~ $bad", l.lowercase().contains(bad))
    }
    @Test fun referenceComparisonsAreAlwaysQualifiedAsInformedReference() {
        assertEquals("Dentro da referência informada", statusLabel(AssessmentStatus.WITHIN_RANGE))
        assertTrue(statusLabel(AssessmentStatus.BELOW_RANGE).endsWith("referência informada"))
        assertTrue(statusLabel(AssessmentStatus.ABOVE_RANGE).endsWith("referência informada"))
    }
    @Test fun abstentionsKeepTheirOwnLabelsAndAreNeverStyledAsReassuring() {
        assertEquals("Precisa de revisão", statusLabel(AssessmentStatus.NEEDS_REVIEW))
        assertEquals("Não classificável", statusLabel(AssessmentStatus.NOT_CLASSIFIABLE))
        assertEquals(ChipKind.REVIEW, statusKind(AssessmentStatus.NEEDS_REVIEW))
        assertEquals(ChipKind.REVIEW, statusKind(AssessmentStatus.NOT_CLASSIFIABLE))
        assertNotEquals(ChipKind.ALERT, statusKind(AssessmentStatus.ABOVE_RANGE))
    }
    @Test fun onlyAFullyProcessedRunIsDescribedAsAllPagesProcessed() {
        for (c in SummaryCompleteness.entries.filter { it != SummaryCompleteness.COMPLETE })
            assertFalse(completenessLabel(c).contains("Todas"))
    }
    @Test fun noUserFacingStringInResultFeatureSourcesClaimsNormality() {
        val files = listOf("../feature-results/src/main", "../feature-history/src/main", "../feature-import/src/main", "../app/src/main")
            .flatMap { File(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
        val phrases = listOf("tudo normal", "está bom", "esta bom", "sem problemas", "resultado normal")
        val hits = files.flatMap { f -> val t = f.readText().lowercase(); phrases.filter { it in t }.map { "${f.name}:$it" } }
        assertTrue("NORMALITY_CLAIM $hits", hits.isEmpty())
    }
}
