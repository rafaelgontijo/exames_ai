package br.com.exames.featureresults

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.exames.domain.model.*
import br.com.exames.domain.summary.*
import br.com.exames.domain.validation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ResultActionsTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(basis: OutputBasis) = ReferenceSummary("a0123456", "doc", ExtractionMode.BASELINE, basis,
        InferenceOutcome.NOT_ATTEMPTED, "rule", ProjectReferenceSummary.VERSION, RunState.PARTIAL,
        SummaryCompleteness.PARTIAL, 1, 2, SummaryCounts(0, 0, 0, 1, 0),
        listOf(SummaryLine("obs", 1, "Glicose", "18", "mg/dL", null, AssessmentStatus.NEEDS_REVIEW,
            ReasonCode.CONTEXT_UNRESOLVED, setOf(Ambiguity.CONTEXT), "GLUCOSE")), setOf(SummaryWarning.PAGES_INCOMPLETE), emptySet())

    @Test fun eachPreviewMatchesSelectedPartialOutputAndCancelNeverPrepares() {
        var basis by mutableStateOf(OutputBasis.AUTOMATIC)
        val prepared = mutableListOf<String>()
        val service = object : ResultSharing {
            override suspend fun prepare(document: String, run: String, summary: SummaryTextResult.Rendered): Uri {
                prepared += summary.text
                throw java.io.IOException()
            }
            override suspend fun revoke(uri: Uri) = fail()
        }
        compose.setContent {
            val rendered = SummaryText.render(summary(basis)) as SummaryTextResult.Rendered
            key(basis) { ShareSummary("doc", "a0123456", rendered, service) }
        }
        for (selected in OutputBasis.entries) {
            compose.runOnIdle { basis = selected }
            compose.onNodeWithText("Compartilhar resumo").performClick()
            val expected = (SummaryText.render(summary(selected)) as SummaryTextResult.Rendered).text
            compose.onNodeWithText(expected).assertExists()
            compose.onNodeWithText("poderá guardar ou transmitir", substring = true).assertExists()
            compose.onNodeWithText("Cancelar").performClick()
            assertTrue(prepared.isEmpty())
        }
        compose.onNodeWithText("Compartilhar resumo").performClick()
        compose.onNodeWithText("Escolher aplicativo").performClick()
        compose.onNodeWithText("Não foi possível compartilhar o resumo.").assertExists()
        assertEquals((SummaryText.render(summary(basis)) as SummaryTextResult.Rendered).text, prepared.single())
    }

    @Test fun lifecycleNavigationDeletionAndSelectionStopEvenBeforeNextCallback() {
        var selected by mutableStateOf<ReferenceSummary?>(summary(OutputBasis.AUTOMATIC))
        var visible by mutableStateOf(true)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle = registry
        }
        var stops = 0
        var calls = 0
        var lastId = ""
        lateinit var completion: (String, Boolean) -> Unit
        val host = object : SpeechHost {
            override val inputLimit = 16
            override fun initialize(ready: (Boolean) -> Unit, finished: (String, Boolean) -> Unit) {
                completion = finished
                ready(true)
            }
            override fun acquireFocus(lost: () -> Unit) = true
            override fun releaseFocus() = Unit
            override fun speak(text: String, id: String): Boolean { lastId = id; calls++; return true }
            override fun stop() { stops++ }
            override fun close() = Unit
        }
        lateinit var speech: OfflineSpeech
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            if (visible) speech = rememberResultSpeech("id", selected) { OfflineSpeech(host) }
        } }
        for (trigger in 1..4) {
            compose.runOnIdle {
                speech.play("Primeiro trecho. Segundo trecho.")
                assertEquals(SpeechState.SPEAKING, speech.state)
                when (trigger) {
                    1 -> owner.registry.currentState = Lifecycle.State.CREATED
                    2 -> selected = summary(OutputBasis.CORRECTED)
                    3 -> selected = null
                    4 -> visible = false
                }
            }
            compose.runOnIdle {
                completion(lastId, true)
                assertEquals(trigger, calls)
                assertEquals(trigger, stops)
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
        }
    }
}
