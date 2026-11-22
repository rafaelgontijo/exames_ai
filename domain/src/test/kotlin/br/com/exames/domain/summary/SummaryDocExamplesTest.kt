package br.com.exames.domain.summary

import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** Keeps docs/REFERENCE_SUMMARY.md honest: its synthetic example must equal the real rendered output. */
class SummaryDocExamplesTest {
    private fun example(): String {
        val line = SummaryLine("o1", 1, "Glicose", "90", "mg/dL", "70-99", AssessmentStatus.WITHIN_RANGE, ReasonCode.INSIDE_REFERENCE, emptySet(), "GLUCOSE")
        val review = SummaryLine("o2", 1, "Ureia", "30 PACIENTE FICTICIO", "mg/dL", "15-45", AssessmentStatus.NEEDS_REVIEW, ReasonCode.AMBIGUOUS_INPUT, setOf(Ambiguity.VALUE), "UREA")
        val s = ReferenceSummary("0123456789abcdef".repeat(4), "doc", ExtractionMode.BASELINE, OutputBasis.AUTOMATIC, InferenceOutcome.NOT_ATTEMPTED,
            "REF_NUMERIC/2.0.0", ProjectReferenceSummary.VERSION, RunState.PARTIAL, SummaryCompleteness.PARTIAL, 1, 2,
            SummaryCounts(0, 1, 0, 1, 0), listOf(line, review), setOf(SummaryWarning.AMBIGUITIES_PRESENT, SummaryWarning.PAGES_INCOMPLETE, SummaryWarning.RUN_NOT_COMPLETE), emptySet())
        return (SummaryText.render(s) as SummaryTextResult.Rendered).text
    }
    @Test fun documentedExampleMatchesRenderedOutputAndHasNoClinicalClaims() {
        val doc = File("../docs/REFERENCE_SUMMARY.md").readText()
        val block = doc.substringAfter("<!-- example:start -->\n```text\n").substringBefore("\n```\n<!-- example:end -->")
        assertEquals(example(), block)
        val t = example().lowercase()
        for (bad in listOf("tudo normal", "está bom", "paciente", "http", "urgên", "doença")) assertFalse(t.contains(bad), bad)
    }
}
