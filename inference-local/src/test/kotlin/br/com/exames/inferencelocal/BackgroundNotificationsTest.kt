package br.com.exames.inferencelocal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundNotificationsTest {
    @Test fun examProgressFormattingAndPercentAreBoundedAndNeutral() {
        assertEquals("Lendo seu exame", BackgroundNotificationCopy.examProgressTitle(useCloud = false))
        assertEquals("Lendo exame (nuvem privada)", BackgroundNotificationCopy.examProgressTitle(useCloud = true))
        assertEquals("Preparando leitura do documento...", BackgroundNotificationCopy.examProgressText(0, 0, 0, 0))
        assertEquals("Página 2 de 6 · Bloco 1 de 3", BackgroundNotificationCopy.examProgressText(2, 6, 1, 3))
        assertNull(BackgroundNotificationCopy.examProgressPercent(0, 0, 0, 0, 0))

        val mid = BackgroundNotificationCopy.examProgressPercent(page = 2, totalPages = 4, block = 1, totalBlocks = 2, completedPages = 1)
        assertEquals(38, mid)
        val capped = BackgroundNotificationCopy.examProgressPercent(page = 4, totalPages = 4, block = 3, totalBlocks = 3, completedPages = 4)
        assertEquals(99, capped)
    }

    @Test fun modelProgressFormattingHandlesDownloadAndVerification() {
        assertNull(BackgroundNotificationCopy.modelProgressPercent(0L, 0L))
        assertEquals(50, BackgroundNotificationCopy.modelProgressPercent(500L, 1000L))
        val text = BackgroundNotificationCopy.modelProgressText(
            downloadedBytes = 1_073_741_824L,
            totalBytes = 2_588_147_712L,
        )
        assertTrue(text.contains("41%"))
        assertTrue(text.contains("GB"))

        val verifying = BackgroundNotificationCopy.modelProgressText(
            downloadedBytes = 2_588_147_712L,
            totalBytes = 2_588_147_712L,
        )
        assertEquals("Verificando integridade (SHA-256)...", verifying)
    }

    @Test fun completionAndFailureTextsRespectNonDiagnosticAndPrivacyBoundaries() {
        val allCopy = listOf(
            BackgroundNotificationCopy.examCompletedTitle(),
            BackgroundNotificationCopy.examCompletedText(),
            BackgroundNotificationCopy.examFailedTitle(),
            BackgroundNotificationCopy.examFailedText(),
            BackgroundNotificationCopy.modelCompletedTitle(),
            BackgroundNotificationCopy.modelCompletedText(),
            BackgroundNotificationCopy.modelFailedTitle(),
            BackgroundNotificationCopy.modelFailedText(ModelPreparationFailure.INSUFFICIENT_SPACE.name),
            BackgroundNotificationCopy.modelFailedText(ModelPreparationFailure.CORRUPT_ASSET.name),
            BackgroundNotificationCopy.modelFailedText("NETWORK_ERROR"),
        ).joinToString(" ").lowercase()

        for (forbidden in listOf("diagnóstico", "urgência", "urgente", "médico", "ubs", "alterado", "doença", "cpf")) {
            assertFalse("Notification copy must not contain '$forbidden'", allCopy.contains(forbidden))
        }
    }
}
