package br.com.exames.app

import org.junit.Assert.*
import org.junit.Test

class ShellRoutesTest {
    @Test fun onlyTheThreeTabsShowTheBottomBar() {
        for (r in ShellRoutes.topLevel) assertTrue(r, ShellRoutes.showsBottomBar(r))
        for (r in listOf(ShellRoutes.SEND, ResultRoutes.PATTERN, null, "unknown")) assertFalse("$r", ShellRoutes.showsBottomBar(r))
    }
    @Test fun fixedRoutesAreStaticNamesWithoutClinicalOrUriContent() {
        for (r in ShellRoutes.fixed) assertTrue(r, Regex("[a-z]{3,10}").matches(r))
        assertEquals(setOf("home", "exams", "help", "send"), ShellRoutes.fixed)
        assertEquals("results/{runId}", ResultRoutes.PATTERN)
        assertTrue(ShellRoutes.isResults(ResultRoutes.PATTERN))
    }
    @Test fun homeIsTheFirstTab() = assertEquals("home", ShellRoutes.topLevel.first())
}
