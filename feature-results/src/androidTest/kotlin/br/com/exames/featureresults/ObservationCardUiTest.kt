package br.com.exames.featureresults

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.ExamesTheme
import br.com.exames.domain.model.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ObservationCardUiTest {
    @get:Rule val compose = createComposeRule()
    private fun row(id: String, status: AssessmentStatus, reason: ReasonCode, result: String, ref: String? = "70-99") =
        ResultRow(Observation(id, "doc", "Glicose", result, "mg/dL", 1, "s", referenceRaw = ref),
            Assessment(id, status, reason, Instant.EPOCH, "rule"))
    private fun show(vararg rows: ResultRow, fontScale: Float = 1f, dark: Boolean = false) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            ExamesTheme(darkTheme = dark) { Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                rows.forEach { ObservationResult(it, "rule", {}, { _, _, _ -> }) }
            } }
        }
    }

    @Test fun needsReviewAndNotClassifiableAreShownWithTheirReasons() {
        show(row("a", AssessmentStatus.NEEDS_REVIEW, ReasonCode.UNIT_UNRESOLVED, "90"),
            row("b", AssessmentStatus.NOT_CLASSIFIABLE, ReasonCode.NO_REFERENCE, "90", ref = null))
        compose.onNodeWithText("Precisa de revisão").assertExists()
        compose.onNodeWithText("A unidade está ausente ou é incompatível com a referência.").assertExists()
        compose.onNodeWithText("Não classificável").assertExists()
        compose.onNodeWithText("Referência: ausente").assertExists()
    }
    @Test fun withinRangeIsQualifiedAndNeverClaimsNormality() {
        show(row("a", AssessmentStatus.WITHIN_RANGE, ReasonCode.INSIDE_REFERENCE, "90"))
        compose.onNodeWithText("Dentro da referência informada").assertExists()
        for (bad in listOf("Tudo normal", "Está bom", "normal")) compose.onAllNodes(hasText(bad, substring = true, ignoreCase = true)).assertCountEquals(0)
    }
    private fun assertLongValueIsNotTruncated(dark: Boolean) {
        val long = "9".repeat(180)
        show(row("a", AssessmentStatus.NEEDS_REVIEW, ReasonCode.AMBIGUOUS_INPUT, long), fontScale = 2f, dark = dark)
        compose.onNodeWithText("Resultado: $long").assertExists()
        val node = compose.onNodeWithText("Resultado: $long").fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        check(layouts.isNotEmpty()) { "NO_TEXT_LAYOUT" }
        check(!layouts.first().hasVisualOverflow) { "RESULT_TEXT_TRUNCATED" }
    }
    @Test fun veryLongValuesAreNotTruncatedAtLargeFontInLightTheme() = assertLongValueIsNotTruncated(dark = false)
    @Test fun veryLongValuesAreNotTruncatedAtLargeFontInDarkTheme() = assertLongValueIsNotTruncated(dark = true)
}
