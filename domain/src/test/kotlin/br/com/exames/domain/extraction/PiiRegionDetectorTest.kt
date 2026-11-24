package br.com.exames.domain.extraction

import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.SourceRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PiiRegionDetectorTest {
    @Test fun detectsCpfEmailPhoneAndAdministrativePatientHeaderWithoutMaskingLabMeasurements() {
        val frags = listOf(
            // Administrative header line 1: Paciente + CPF
            word("p1", "Paciente:", 20.0, 40.0, 80.0),
            word("p2", "Maria", 86.0, 40.0, 120.0),
            word("p3", "Silva", 126.0, 40.0, 160.0),
            word("p4", "CPF:", 220.0, 40.0, 250.0),
            word("p5", "123.456.789-00", 256.0, 40.0, 340.0),
            // Administrative header line 2: Email + Phone
            word("c1", "Email:", 20.0, 60.0, 65.0),
            word("c2", "pessoa@exemplo.com", 70.0, 60.0, 190.0),
            word("c3", "Telefone:", 210.0, 60.0, 265.0),
            word("c4", "(11)", 270.0, 60.0, 295.0),
            word("c5", "98765-4321", 300.0, 60.0, 370.0),
            // Clinical measurement line (MUST NOT be masked!)
            word("m1", "Glicose", 20.0, 140.0, 80.0),
            word("m2", "92", 180.0, 140.0, 200.0),
            word("m3", "mg/dL", 205.0, 140.0, 245.0),
            word("m4", "70", 300.0, 140.0, 320.0),
            word("m5", "a", 324.0, 140.0, 332.0),
            word("m6", "99", 336.0, 140.0, 356.0),
            word("m7", "mg/dL", 360.0, 140.0, 400.0),
        )

        val redactions = PiiRegionDetector.detect(frags)
        assertTrue(redactions.isNotEmpty())
        // Patient name box covered
        assertTrue(redactions.any { it.top == 40.0 && it.left <= 86.0 && it.right >= 160.0 })
        // CPF box covered
        assertTrue(redactions.any { it.top == 40.0 && it.left <= 256.0 && it.right >= 340.0 })
        // Email box covered
        assertTrue(redactions.any { it.top == 60.0 && it.left <= 70.0 && it.right >= 190.0 })
        // Phone box covered
        assertTrue(redactions.any { it.top == 60.0 && it.left <= 270.0 && it.right >= 370.0 })
        // Clinical measurement line at y=140 MUST NOT be redacted
        assertFalse(redactions.any { it.top == 140.0 })
    }

    @Test fun returnsEmptyForScannedOrEmptyPagesWithoutPositionedText() {
        assertEquals(emptyList<SourceRegion>(), PiiRegionDetector.detect(emptyList()))
        val unpositioned = listOf(SourceFragment("u1", "123.456.789-00", null, "PDF_TEXT", "1"))
        assertEquals(emptyList<SourceRegion>(), PiiRegionDetector.detect(unpositioned))
    }

    private fun word(id: String, text: String, x: Double, y: Double, right: Double) =
        SourceFragment(id, text, SourceRegion(1, x, y, right, y + 12.0, 500.0, 800.0), "PDF_TEXT", "1")
}
