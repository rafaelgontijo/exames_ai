package br.com.exames.domain.evaluator

import br.com.exames.domain.model.ResultComparator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

class ConservativeNumberParserTest {
    private val parser = ConservativeNumberParser()
    @ParameterizedTest @CsvSource(delimiter = ';', value = [
        "12,5;12.5", "12.5;12.5", "-0,1;-0.1", "1.234,56;1234.56", "1,234.56;1234.56", "42;42",
    ])
    fun explicitUnambiguousFormats(raw: String, expected: String) {
        val result = parser.parse(raw)
        assertEquals(0, expected.toBigDecimal().compareTo(result.value))
        assertFalse(result.needsReview)
    }
    @ParameterizedTest @ValueSource(strings = ["1,234", "1.234", "1.2.3", "1,23.4", "NaN", "positivo", "", "12 mg/dL", "1 234", "1,234,567"])
    fun uncertainNumbersAreNotGuessed(raw: String) {
        val result = parser.parse(raw)
        assertTrue(result.needsReview)
        assertNull(result.value)
    }
    @ParameterizedTest @CsvSource(delimiter = ';', value = [
        "< 0,1;LT", "<= 0.1;LTE", "> 10;GT", ">= 10;GTE", "≤ 2;LTE", "≥ 2;GTE",
    ])
    fun operatorsArePreservedAndRequireReview(raw: String, comparator: ResultComparator) {
        val result = parser.parse(raw)
        assertEquals(comparator, result.comparator)
        assertTrue(result.needsReview)
        assertNotNull(result.value)
    }
    @Test fun exactComparisonDoesNotRequireReview() {
        assertEquals(ResultComparator.EQ, parser.parse("= 12,5").comparator)
        assertFalse(parser.parse("= 12,5").needsReview)
    }
}
