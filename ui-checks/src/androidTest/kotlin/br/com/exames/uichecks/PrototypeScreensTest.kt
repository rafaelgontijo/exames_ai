package br.com.exames.uichecks

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.coreui.ExamesTheme
import br.com.exames.domain.model.Document
import br.com.exames.domain.summary.*
import br.com.exames.domain.validation.*
import br.com.exames.featurehistory.HelpScreen
import br.com.exames.featurehistory.HomeContent
import br.com.exames.featurehistory.HomeUiState
import br.com.exames.featureimport.ImportContent
import br.com.exames.featureimport.ImportUiState
import br.com.exames.featureresults.ResultsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Four screens x light/dark x font 1x/2x: accessibility checks, 48 dp targets, reachable by scrolling, and synthetic
 * captures (no clinical data) written for docs/prototype-screens.
 */
class PrototypeScreensTest {
    @get:Rule val compose = createComposeRule()
    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }

    private fun loadedHome() = HomeUiState(false, HomeState.Loaded(listOf(HomeDocumentCard("doc-sintetico", DocumentFormat.PDF,
        Instant.parse("2026-01-01T12:00:00Z"), listOf(
            ModeExtraction(ExtractionMode.BASELINE, "r1", RunState.COMPLETE, InferenceOutcome.NOT_ATTEMPTED, "2026-01-01T12:00:00Z"),
            ModeExtraction(ExtractionMode.ASSISTED, "r2", RunState.PARTIAL, InferenceOutcome.FALLBACK, "2026-01-01T12:05:00Z")), 1))))

    private val screens: Map<String, @Composable () -> Unit> = mapOf(
        "inicio-vazio" to { HomeContent(HomeUiState(false, HomeState.Empty), {}, {}, {}, {}) },
        "inicio-com-exame" to { HomeContent(loadedHome(), {}, {}, {}, {}) },
        "enviar" to { ImportContent(ImportUiState(runId = "r", runState = RunState.RUNNING, completedPages = 1, totalPages = 3), {}, {}, {}, {}, {}, {}, {}, {}, {}) },
        "ajuda" to { HelpScreen(onExams = {}) },
        "resultado" to { ResultsScreen(SyntheticResults.RUN_ID, onBack = {}, model = SyntheticResults.model()) },
    )

    private data class Variant(val name: String, val dark: Boolean, val fontScale: Float)
    private var variant by mutableStateOf(Variant("inicio-vazio", false, 1f))
    private var started = false

    /** One composition for the whole test; variants swap through state because setContent may run once. */
    private fun render(name: String, dark: Boolean, fontScale: Float) {
        if (!started) {
            started = true
            compose.setContent {
                val v = variant
                key(v) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, v.fontScale)) {
                        ExamesTheme(darkTheme = v.dark) {
                            // Same root as MainActivity: the themed background token, not an unthemed window.
                            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { screens.getValue(v.name)() }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { variant = Variant(name, dark, fontScale) }
        compose.waitForIdle()
        if (name == "resultado") compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("results-summary")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun verify(name: String, dark: Boolean, fontScale: Float) {
        render(name, dark, fontScale)
        // Every actionable control is at least 48 dp tall and wide.
        val clickable = compose.onAllNodes(hasClickAction())
        val count = clickable.fetchSemanticsNodes().size
        assertTrue("$name has no actions", count > 0)
        val density = compose.density.density
        for (i in 0 until count) {
            // Layout size, not clipped bounds: controls below the fold still have to be 48 dp.
            val size = clickable[i].fetchSemanticsNode().size
            assertTrue("$name dark=$dark font=$fontScale control $i too short: ${size.height / density}dp", size.height / density >= 47.5f)
            assertTrue("$name dark=$dark font=$fontScale control $i too narrow: ${size.width / density}dp", size.width / density >= 47.5f)
            // Plain scrolling pages must let the user reach every control (the results list is lazy and checked by its own tests).
            if (name != "resultado") clickable[i].performScrollTo().assertIsDisplayed()
        }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private fun capture(name: String, dark: Boolean, fontScale: Float) {
        render(name, dark, fontScale)
        val image = if (android.os.Build.VERSION.SDK_INT >= 26) compose.onRoot().captureToImage().asAndroidBitmap()
            else requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(outDir, "$name-${if (dark) "escuro" else "claro"}-${(fontScale * 100).toInt()}.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun allScreensPassAccessibilityChecksAndTouchTargetsInBothThemesAndFontScales() {
        compose.enableAccessibilityChecks()
        for (name in screens.keys) for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) verify(name, dark, scale)
    }

    @Test fun capturesSyntheticScreensForTheDocumentation() {
        for (name in screens.keys) for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) capture(name, dark, scale)
        assertEquals(screens.size * 4, outDir.listFiles().orEmpty().count { it.extension == "png" })
    }
}
