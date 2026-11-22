package br.com.exames.domain.summary

import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*

sealed interface SummaryTextResult {
    /** [text] is the exact content previewed, spoken and exported; [utf8Bytes] is its encoded size. */
    data class Rendered(val text: String, val utf8Bytes: Int, val omittedFieldCount: Int, val identity: String) : SummaryTextResult
    /** Explicit failure; the text is never truncated to fit. */
    data class TooLarge(val utf8Bytes: Int, val limitBytes: Int) : SummaryTextResult
}

/**
 * One deterministic text for preview, audio and export. Only structured referential fields are copied, and
 * only when they match conservative shapes; any other free text becomes a visible review marker pointing to
 * the private source. No headers, URIs, patient identity, model reasoning or clinical wording is produced.
 */
object SummaryText {
    const val VERSION = "SUMMARY_TEXT/1.1.0"
    const val LIMIT_BYTES = 64 * 1024
    const val OMISSION_MARKER = "[conferir na fonte privada]"

    private val units = setOf("mg/dL", "g/dL", "mmol/L", "mEq/L", "µmol/L", "μmol/L", "umol/L", "mg/L", "U/L", "%")
    private val number = "\\d{1,7}(?:[.,]\\d{1,6})?"
    private val resultShape = Regex("(?:[<>≤≥=]|<=|>=)?\\s?$number")
    private val unitPattern = units.joinToString("|") { Regex.escape(it) }
    private val referenceShape = Regex("(?:$number\\s?(?:-|–|a|até)\\s?$number|(?:<=|>=|[<>≤≥])\\s?$number)(?: +(?:$unitPattern))?")

    fun render(summary: ReferenceSummary, limitBytes: Int = LIMIT_BYTES): SummaryTextResult {
        var omitted = 0
        fun field(raw: String?, accept: (String) -> Boolean): String? {
            if (raw == null) return null
            val t = raw.trim()
            if (t.isNotEmpty() && t.length <= 40 && accept(t)) return t
            omitted++
            return OMISSION_MARKER
        }
        val runLabel = summary.runId.takeIf(ProcessingRunIds::isOpaque)?.take(8) ?: OMISSION_MARKER
        val ruleLabel = field(summary.ruleVersion) { Regex("""REF_NUMERIC/\d{1,3}\.\d{1,3}\.\d{1,3}""").matches(it) }
        val identity = "${basisLabel(summary.basis)}, modo ${modeLabel(summary.mode)}, execução $runLabel"
        val sb = StringBuilder()
        sb.append("Resumo referencial (").append(identity).append(").\n")
        sb.append("Estado da execução: ").append(completenessLabel(summary)).append(".\n")
        if (summary.mode == ExtractionMode.ASSISTED) sb.append(summary.participation?.label ?: "Participação da IA sem registro.").append('\n')
        sb.append("Regra ").append(ruleLabel).append(", resumo ").append(VERSION).append(".\n")
        val c = summary.counts
        sb.append("Contagens: abaixo da referência informada ").append(c.below)
            .append(", dentro da referência informada ").append(c.within)
            .append(", acima da referência informada ").append(c.above)
            .append(", requer revisão ").append(c.needsReview)
            .append(", não classificável ").append(c.notClassifiable).append(".\n")
        if (summary.lines.isEmpty()) sb.append("Nenhum campo suportado foi encontrado.\n")
        summary.lines.forEachIndexed { i, l ->
            val analyte = analyteLabel(l.analyteNormalized) ?: run { omitted++; OMISSION_MARKER }
            val result = field(l.resultRaw) { resultShape.matches(it) }
            val unit = field(l.unitRaw) { it in units }
            val reference = field(l.referenceRaw) { referenceShape.matches(it) }
            sb.append(i + 1).append(". ").append(analyte).append(" (página ").append(l.page).append("): resultado ")
                .append(result ?: "ausente").append(", unidade ").append(unit ?: "ausente")
                .append(", referência do documento ").append(reference ?: "ausente")
                .append(". Situação: ").append(statusLabel(l.status))
            l.reason?.let { sb.append(". ").append(assessmentReason(it).trimEnd('.')) }
            if (l.ambiguities.isNotEmpty())
                sb.append("; ambiguidades ").append(l.ambiguities.sortedBy { it.name }.map { when (it) {
                    Ambiguity.VALUE -> "valor"
                    Ambiguity.UNIT -> "unidade"
                    Ambiguity.CONTEXT -> "contexto"
                    Ambiguity.COMPARATOR -> "comparador"
                    Ambiguity.REFERENCE_ASSOCIATION -> "associação da referência"
                } }.joinToString(", "))
            sb.append(".\n")
        }
        if (summary.warnings.isNotEmpty())
            sb.append("Limitações: ").append(summary.warnings.sortedBy { it.name }.map(::summaryWarning).joinToString(", ")).append(".\n")
        if (omitted > 0)
            sb.append(omitted).append(" campo(s) omitido(s) por texto livre ou não suportado; conferir na fonte privada.\n")
        sb.append("Comparação com referências informadas no documento; não é diagnóstico nem orientação clínica.")
        val text = sb.toString()
        val size = text.toByteArray(Charsets.UTF_8).size
        return if (size > limitBytes) SummaryTextResult.TooLarge(size, limitBytes)
        else SummaryTextResult.Rendered(text, size, omitted, identity)
    }

    private fun analyteLabel(normalized: String?): String? = when (normalized) {
        Analyte.GLUCOSE.name -> "Glicose"; Analyte.UREA.name -> "Ureia"; Analyte.CREATININE.name -> "Creatinina"
        Analyte.SODIUM.name -> "Sódio"; Analyte.POTASSIUM.name -> "Potássio"; else -> null
    }
    private fun basisLabel(b: OutputBasis) = when (b) {
        OutputBasis.AUTOMATIC -> "saída automática"; OutputBasis.CORRECTED -> "saída corrigida"; OutputBasis.REASSESSED -> "saída reavaliada"
    }
    private fun modeLabel(m: ExtractionMode) = when (m) { ExtractionMode.BASELINE -> "sem IA"; ExtractionMode.ASSISTED -> "com IA local" }
    private fun statusLabel(s: AssessmentStatus) = when (s) {
        AssessmentStatus.BELOW_RANGE -> "abaixo da referência informada"
        AssessmentStatus.WITHIN_RANGE -> "dentro da referência informada"
        AssessmentStatus.ABOVE_RANGE -> "acima da referência informada"
        AssessmentStatus.NEEDS_REVIEW -> "requer revisão"
        AssessmentStatus.NOT_CLASSIFIABLE -> "não classificável"
    }
    private fun completenessLabel(s: ReferenceSummary) = when (s.completeness) {
        SummaryCompleteness.COMPLETE -> "páginas completas (${s.pagesComplete} de ${s.pagesExpected})"
        SummaryCompleteness.PARTIAL -> "parcial, ${s.pagesComplete} página(s) completa(s)"
        SummaryCompleteness.IN_PROGRESS -> "em andamento"
        SummaryCompleteness.UNAVAILABLE -> "indisponível"
    }
}
