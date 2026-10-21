package br.com.exames.domain.evaluator

import br.com.exames.domain.model.ResultComparator
import java.math.BigDecimal

data class ParsedNumber(val value: BigDecimal?, val comparator: ResultComparator, val needsReview: Boolean)

/** Single 3-digit separators are ambiguous (1,234 / 1.234); never guess locale. */
class ConservativeNumberParser {
    fun parse(raw: String): ParsedNumber {
        val match = Regex("""^\s*(<=|>=|<|>|≤|≥|=)?\s*([+-]?[0-9]+(?:[.,][0-9]+)*)\s*$""").matchEntire(raw)
            ?: return ParsedNumber(null, ResultComparator.UNKNOWN, true)
        val op = when (match.groupValues[1]) {
            "<" -> ResultComparator.LT; "<=", "≤" -> ResultComparator.LTE
            ">" -> ResultComparator.GT; ">=", "≥" -> ResultComparator.GTE
            else -> ResultComparator.EQ
        }
        val number = match.groupValues[2]
        val groupedComma = Regex("""[+-]?[0-9]{1,3}(?:,[0-9]{3})+\.[0-9]+""")
        val groupedDot = Regex("""[+-]?[0-9]{1,3}(?:\.[0-9]{3})+,[0-9]+""")
        val canonical = when {
            groupedComma.matches(number) -> number.replace(",", "")
            groupedDot.matches(number) -> number.replace(".", "").replace(',', '.')
            Regex("""[+-]?[0-9]+[.,][0-9]{3}""").matches(number) -> null
            Regex("""[+-]?[0-9]+(?:[.,][0-9]+)?""").matches(number) -> number.replace(',', '.')
            else -> null
        }
        val value = canonical?.toBigDecimalOrNull()
        return ParsedNumber(value, op, value == null || op != ResultComparator.EQ)
    }
}
