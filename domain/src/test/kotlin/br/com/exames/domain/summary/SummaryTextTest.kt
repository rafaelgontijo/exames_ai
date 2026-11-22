package br.com.exames.domain.summary

import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SummaryTextTest {
    private fun line(i: Int = 1, analyte: String? = "GLUCOSE", result: String = "90", unit: String? = "mg/dL", ref: String? = "70-99",
        status: AssessmentStatus = AssessmentStatus.WITHIN_RANGE, reason: ReasonCode? = ReasonCode.INSIDE_REFERENCE,
        amb: Set<Ambiguity> = emptySet(), analyteRaw: String = "Glicose") =
        SummaryLine("o$i", 1, analyteRaw, result, unit, ref, status, reason, amb, analyte)
    private fun summary(lines: List<SummaryLine>, basis: OutputBasis = OutputBasis.AUTOMATIC, mode: ExtractionMode = ExtractionMode.BASELINE,
        completeness: SummaryCompleteness = SummaryCompleteness.COMPLETE, warnings: Set<SummaryWarning> = emptySet()) =
        ReferenceSummary("abcdef0123456789".repeat(4), "doc-interno", mode, basis, InferenceOutcome.NOT_ATTEMPTED, "REF_NUMERIC/2.0.0",
            ProjectReferenceSummary.VERSION, RunState.COMPLETE, completeness, 1, 1,
            SummaryCounts(0, lines.count { it.status == AssessmentStatus.WITHIN_RANGE }, 0, lines.count { it.status == AssessmentStatus.NEEDS_REVIEW }, 0),
            lines, warnings, emptySet())
    private fun rendered(s: ReferenceSummary) = SummaryText.render(s) as SummaryTextResult.Rendered

    @Test fun exactByteBoundaryIsAcceptedAndOneByteLessFailsWithoutTruncation() {
        val s = summary(listOf(line()))
        val size = rendered(s).utf8Bytes
        assertTrue(SummaryText.render(s, size) is SummaryTextResult.Rendered)
        val failed = SummaryText.render(s, size - 1)
        assertEquals(SummaryTextResult.TooLarge(size, size - 1), failed)
    }
    @Test fun oversizedSummaryFailsExplicitlyAtDefaultLimit() {
        val r = SummaryText.render(summary((1..1500).map { line(it) }))
        r as SummaryTextResult.TooLarge
        assertEquals(64 * 1024, r.limitBytes)
        assertTrue(r.utf8Bytes > r.limitBytes)
    }
    @Test fun sizeIsMeasuredInUtf8BytesNotCharacters() {
        val r = rendered(summary(listOf(line(analyte = "SODIUM", unit = "µmol/L"))))
        assertTrue(r.text.contains("Sódio") && r.text.contains("µmol/L"))
        assertTrue(r.utf8Bytes > r.text.length)
        assertEquals(r.text.toByteArray(Charsets.UTF_8).size, r.utf8Bytes)
    }
    @Test fun identityMarkerInFreeTextIsOmittedAndFlagged() {
        val marker = "PACIENTE: MARIA SINTETICA CPF 000.000.000-00"
        val r = rendered(summary(listOf(line(result = "90 $marker", unit = marker, ref = marker, analyte = null, analyteRaw = marker))))
        assertFalse(r.text.contains("MARIA")); assertFalse(r.text.contains("CPF")); assertFalse(r.text.contains("PACIENTE"))
        assertEquals(4, r.omittedFieldCount)
        assertTrue(r.text.contains(SummaryText.OMISSION_MARKER))
        assertTrue(r.text.contains("4 campo(s) omitido(s)"))
    }
    @Test fun unknownOperationalMetadataCannotIntroduceFreeText() {
        val r = rendered(summary(listOf(line())).copy(runId = "NAME-TEST!", ruleVersion = "NAME-TEST!"))
        assertFalse(r.text.contains("NAME-TEST"))
        assertTrue(r.text.contains(SummaryText.OMISSION_MARKER))
    }

    @Test fun unknownUnitAndLongFieldsAreOmitted() {
        val r = rendered(summary(listOf(line(unit = "furlongs/fortnight", result = "9".repeat(41)))))
        assertEquals(2, r.omittedFieldCount)
    }
    @Test fun noOmissionsForStructuredFieldsAndMissingFieldsAreSaidAbsentNotInvented() {
        val r = rendered(summary(listOf(line(ref = null, unit = null))))
        assertEquals(0, r.omittedFieldCount)
        assertTrue(r.text.contains("unidade ausente") && r.text.contains("referência do documento ausente"))
        assertFalse(r.text.contains(SummaryText.OMISSION_MARKER))
    }
    @Test fun identityStatesBasisModeAndRun() {
        val r = rendered(summary(listOf(line()), OutputBasis.CORRECTED, ExtractionMode.ASSISTED))
        assertTrue(r.text.contains("saída corrigida") && r.text.contains("com IA local") && r.text.contains("abcdef01"))
        assertTrue(rendered(summary(listOf(line()), OutputBasis.REASSESSED)).text.contains("saída reavaliada"))
        assertTrue(rendered(summary(listOf(line()))).text.contains("saída automática") )
        assertFalse(r.text.contains("doc-interno"))
    }
    @Test fun noHeadersUrisOrNormalityClaims() {
        val r = rendered(summary(listOf(line(), line(2, status = AssessmentStatus.NEEDS_REVIEW, reason = ReasonCode.UNIT_UNRESOLVED, amb = setOf(Ambiguity.UNIT)))))
        val t = r.text.lowercase()
        for (bad in listOf("content://", "file://", "http", "tudo normal", "está bom", "paciente", "nome:", "urgên"))
            assertFalse(t.contains(bad), bad)
        assertTrue(t.contains("dentro da referência informada") && t.contains("requer revisão") && t.contains("ambiguidades unidade"))
    }
    @Test fun partialAndWarningsArePreserved() {
        val r = rendered(summary(listOf(line()), completeness = SummaryCompleteness.PARTIAL,
            warnings = setOf(SummaryWarning.PAGES_INCOMPLETE, SummaryWarning.REFERENCE_MISSING)))
        assertTrue(r.text.contains("parcial") && r.text.contains("páginas incompletas") && r.text.contains("há campos sem referência no documento"))
    }
    @Test fun emptySummaryStatesNoFieldsAndIsDeterministic() {
        val s = summary(emptyList())
        assertTrue(rendered(s).text.contains("Nenhum campo suportado"))
        assertEquals(rendered(s), rendered(s))
    }
    @Test fun preservesReferenceUnitsAndRejectsTrailingFreeText() {
        val safe = rendered(summary(listOf(line(ref = "10 a 21 mg/dL"), line(2, ref = "< 4 mmol/L"))))
        assertEquals(0, safe.omittedFieldCount)
        assertTrue(safe.text.contains("referência do documento 10 a 21 mg/dL"))
        val unsafe = rendered(summary(listOf(line(ref = "10 a 21 mg/dL TESTE-ZETA"))))
        assertEquals(1, unsafe.omittedFieldCount)
        assertFalse(unsafe.text.contains("TESTE-ZETA"))
    }

    @Test fun comparatorResultsAndRangesWithCommaAreAccepted() {
        val r = rendered(summary(listOf(line(result = "< 0,1", ref = "≥ 10"), line(2, result = "1.234,5".replace(".", ""), ref = "0,5 a 1,2"))))
        assertEquals(0, r.omittedFieldCount)
    }
}
