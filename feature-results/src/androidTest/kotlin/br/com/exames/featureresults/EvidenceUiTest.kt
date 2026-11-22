package br.com.exames.featureresults

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class EvidenceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun evidence(region: SourceRegion) = ExtractedFieldEvidence("resultRaw", "15", region, "synthetic", "SYNTHETIC", "1")

    @Test fun overlayPaintsTheCitedBoundsAfterScalingWithoutApplyingOrientationTwice() {
        var rotation by mutableStateOf(0)
        compose.setContent { MaterialTheme {
            val w = if (rotation % 180 == 0) 200 else 100
            val h = if (rotation % 180 == 0) 100 else 200
            val bitmap = remember(w, h) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) } }
            val region = SourceRegion(2, .2*w, .2*h, .6*w, .6*h, w.toDouble(), h.toDouble(), rotation)
            Column(Modifier.width(200.dp)) { EvidenceSourcePreview(bitmap, 2,
                SelectedEvidence(EvidenceOrigin.EXTRACTED, "Resultado extraído", "15", listOf(evidence(region)))) }
        } }
        for (value in listOf(0, 90, 180, 270)) {
            compose.runOnIdle { rotation = value }
            compose.onNodeWithTag("evidence-overlay").assertExists()
            val node = compose.onNodeWithTag("private-preview")
            val image = if (android.os.Build.VERSION.SDK_INT >= 26) node.captureToImage() else {
                // The Window overload of PixelCopy is absent on API 24.
                val bounds = node.fetchSemanticsNode().boundsInWindow
                compose.runOnIdle {
                    val view = compose.activity.window.decorView
                    val clipped = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(clipped).apply { translate(-bounds.left, -bounds.top) })
                    clipped.asImageBitmap()
                }
            }
            val pixels = image.toPixelMap()
            val x = (pixels.width * .2).toInt()
            val y = (pixels.height * .5).toInt()
            val row = (0 until pixels.width).filter {
                val edge = pixels[it, y]
                edge.red < .2f && edge.blue > .5f
            }
            assertTrue("CITED_BORDER_NOT_PAINTED rotation=$value expected=$x found=$row size=${pixels.width}x${pixels.height}", row.any { kotlin.math.abs(it - x) <= 2 })
            val outside = pixels[(pixels.width*.9).toInt(), (pixels.height*.9).toInt()]
            assertEquals(1f, outside.red, .01f); assertEquals(1f, outside.green, .01f); assertEquals(1f, outside.blue, .01f)
        }
    }

    @Test fun mixedIncompatibleCoordinateFramesDoNotProducePartialOrInventedHighlights() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val valid = SourceRegion(1, 10.0, 10.0, 50.0, 30.0, 100.0, 200.0)
        val incompatible = valid.copy(pageWidth = 200.0, pageHeight = 100.0)
        compose.setContent { MaterialTheme { Column {
            EvidenceSourcePreview(bitmap, 1, SelectedEvidence(EvidenceOrigin.EXTRACTED, "Referência extraída", "synthetic",
                listOf(evidence(valid), evidence(incompatible))))
        } } }
        compose.onNodeWithTag("private-preview").assertExists()
        compose.onNodeWithTag("evidence-overlay").assertDoesNotExist()
        compose.onNodeWithText("Regiões indisponíveis", substring = true).assertExists()
    }

    @Test fun documentaryReviewNeverHidesAbstentionOrChangesTheOriginalResult() {
        val original = Observation("obs", "doc", "Glicose", "15", null, 1, "synthetic")
        var row by mutableStateOf(ResultRow(original,
            Assessment("obs", AssessmentStatus.NEEDS_REVIEW, ReasonCode.AMBIGUOUS_INPUT, Instant.EPOCH, "rule")))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ObservationResult(row, "rule", {}, { _, _, _ -> })
        } } }
        compose.onNodeWithText("Sem revisão documental registrada").assertExists()
        compose.onNodeWithText("Precisa de revisão").assertExists()
        compose.runOnIdle { row = row.copy(observation = original.copy(confirmed = true)) }
        compose.onNodeWithText("Revisão documental registrada").assertExists()
        compose.onNodeWithText("Precisa de revisão").assertExists()
        compose.runOnIdle { row = ResultRow(original, row.assessment!!.copy(status = AssessmentStatus.NOT_CLASSIFIABLE,
            reasonCode = ReasonCode.NO_REFERENCE), listOf(ManualCorrection("correction", "obs", CorrectionField.RESULT, "15", "16", Instant.EPOCH))) }
        compose.onNodeWithText("Não classificável").assertExists()
        compose.onNodeWithText("Revisão documental registrada").assertExists()
        compose.onNodeWithText("Resultado: 15").assertExists()
        compose.onNodeWithText("Antes: 15. Depois: 16.").assertExists()
        compose.runOnIdle { row = row.copy(reference = ReferenceRange("obs", "10 a 20 u/L", "u/L", 1)) }
        compose.onNodeWithText("Referência: 10 a 20 u/L").assertExists()
        compose.onNodeWithText("Não classificável").assertExists()
    }

    @Test fun reviewedGroundTruthStillShowsExpectedNeedsReviewAndPendingVersionStaysOutsideMetrics() {
        val expected = ExpectedObservation("expected", Analyte.GLUCOSE, "Glicose", "15", null, null, null,
            null, null, ExpectedStatus.NEEDS_REVIEW, "CONTEXT_UNRESOLVED", true, emptyList())
        var truth by mutableStateOf(GroundTruthVersion(caseId = "case", version = "1", reviewState = ReviewState.REVIEWED,
            reviewerId = "synthetic", reviewedAt = Instant.EPOCH.toString(), observations = listOf(expected)))
        compose.setContent { MaterialTheme { Column {
            GroundTruthResult(GroundTruthSnapshot("a".repeat(64), truth)) { _, _, _ -> }
        } } }
        compose.onNodeWithText("Estado esperado: Precisa de revisão").assertExists()
        compose.onNodeWithText("O contexto necessário à referência não foi resolvido.").assertExists()
        compose.runOnIdle { truth = truth.copy(version = "2", reviewState = ReviewState.PENDING, reviewerId = null, reviewedAt = null) }
        compose.onNodeWithText("Gabarito versão 2").assertExists()
        compose.onNodeWithText("Revisão pendente — fora das métricas de acerto").assertExists()
        compose.onNodeWithText("Estado esperado: Precisa de revisão").assertExists()
    }
}
