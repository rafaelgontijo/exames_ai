package br.com.exames.app

import org.junit.Assert.*
import org.junit.Test

class ResultRoutesTest {
    @Test fun navigationAcceptsOnlyOpaqueRunIdentities() {
        for (id in listOf("a".repeat(64), "19caa744-48bf-4e9b-98ba-63b7038ddc3a")) assertEquals("results/$id", ResultRoutes.route(id))
        for (value in listOf("content://synthetic-private-file", "Glicose 15 mg/dL", "patient-name", "../document", "")) {
            try { ResultRoutes.route(value); fail("SENSITIVE_OR_INVALID_ROUTE_ACCEPTED") } catch (_: IllegalArgumentException) { }
        }
    }
}
