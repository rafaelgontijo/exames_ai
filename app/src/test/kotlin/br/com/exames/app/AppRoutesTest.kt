package br.com.exames.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoutesTest {
    @Test fun routesAcceptOpaqueIdsAndRejectClinicalTextOrUris() {
        val valid = "11111111-1111-1111-1111-111111111111"
        assertTrue(AppRoutes.isOpaqueId(valid))
        assertEquals("processing/$valid", AppRoutes.processing(valid))
        assertEquals("results/$valid", AppRoutes.results(valid))
        assertEquals("evidence/$valid", AppRoutes.evidence(valid))

        for (bad in listOf("", "content://pdf/1", "Glicose 118 mg/dL", "a/b", "a?b=c")) {
            assertFalse("Expected rejection for: $bad", AppRoutes.isOpaqueId(bad))
        }
    }
}
