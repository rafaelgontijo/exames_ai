package br.com.exames.app

import android.app.ActivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.EncryptedExamHistoryStore
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.inference.GenerationRequest
import br.com.exames.domain.inference.GenerationResult
import br.com.exames.domain.inference.TextInference
import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.inferencelocal.LocalTextInference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateExamAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun extractAuthorizedPdfOffline() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("ownerApproved") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.packageName == "br.com.exames.offline.validation.extraction")
        check(Build.MODEL == "SM-S931B")
        check(Settings.Global.getInt(target.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        val wifiState = Settings.Global.getInt(target.contentResolver, Settings.Global.WIFI_ON, 0)
        check(wifiState == 0 || wifiState == 3)
        val page = args.getString("ownerPage")?.toIntOrNull()
        check(page == null || page in 1..br.com.exames.domain.extraction.ExtractExam.MAX_PAGES)
        val usePicker = args.getString("viaPicker") == "true"
        val keepReview = args.getString("ownerReview") == "true"
        val cancelInference = args.getString("cancelInference") == "true"
        check(!usePicker || page == null && (cancelInference || args.getString("compareChecklist") == "true"))

        fun emit(phase: String, fields: JSONObject = JSONObject()) {
            fields.put("phase", phase)
            instrumentation.sendStatus(2, Bundle().apply { putString("examExtraction", fields.toString()) })
        }

        val store = EncryptedExamHistoryStore(target)
        check(store.list().isEmpty())
        val activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        var retained = false
        try {
            val (id, result) = if (usePicker) {
                val picker = ExamPickerAutomation(instrumentation.uiAutomation)
                val model = viewModel(activity)
                ui("PRIVATE_PICKER_ENTRY") {
                    compose.onNodeWithText("Selecionar PDF").performClick()
                    picker.waitForPicker()
                }
                emit("authorized-stream-ready")
                try {
                    picker.choose(AuthorizedExamDocumentsProvider.AUTHORIZED_LABEL)
                } catch (_: IllegalStateException) {
                    picker.waitForPicker()
                    emit("authorized-picker-awaiting-selection")
                    ui("PRIVATE_PICKER_MANUAL_TIMEOUT") {
                        compose.waitUntil(120_000) {
                            val state = model.state.value
                            state.processing?.cancellable == true || state.openedExam != null || state.notice != null
                        }
                    }
                }
                if (cancelInference) {
                    val processes = target.getSystemService(ActivityManager::class.java)
                    fun activeInference() = processes.runningAppProcesses.orEmpty().any {
                        it.processName == target.packageName + ":inference"
                    }
                    ui("PRIVATE_CANCELLATION_FAILED") {
                        compose.waitUntil(45_000) {
                            (model.state.value.processing?.block ?: 0) > 0 && activeInference()
                        }
                        compose.onNodeWithText("Cancelar leitura", substring = false).performSemanticsAction(SemanticsActions.OnClick)
                        try {
                            compose.waitUntil(30_000) {
                                val current = model.state.value
                                current.processing == null && current.notice == "Extração cancelada." && !activeInference()
                            }
                        } finally {
                            val current = model.state.value
                            emit(
                                "authorized-cancellation-state",
                                JSONObject()
                                    .put("entry", current.processing == null && current.openedExam == null)
                                    .put("finished", current.openedExam != null)
                                    .put("cancelNotice", current.notice == "Extração cancelada.")
                                    .put("processActive", activeInference()),
                            )
                        }
                    }
                    check(store.list().isEmpty()) { "PRIVATE_CANCELLATION_SOURCE_RETAINED" }
                    emit(
                        "authorized-cancellation-measured",
                        JSONObject()
                            .put("stoppedDuringInference", true)
                            .put("privateProcessRetired", true)
                            .put("sourceDiscarded", true),
                    )
                    return@runBlocking
                }
                var last = Triple(0, 0, 0)
                ui("PRIVATE_PICKER_RESULT_TIMEOUT") {
                    compose.waitUntil(1_730_000) {
                        val state = model.state.value
                        val proc = state.processing
                        when {
                            proc != null -> {
                                val index = Triple(proc.page, proc.block, proc.totalBlocks)
                                if (last != index) {
                                    last = index
                                    emit(
                                        "processing",
                                        JSONObject().put("page", proc.page).put("pages", proc.totalPages)
                                            .put("block", proc.block).put("blocks", proc.totalBlocks),
                                    )
                                }
                                false
                            }
                            state.openedExam != null -> true
                            else -> {
                                check(state.notice == null) { "PRIVATE_PICKER_IMPORT_FAILED" }
                                false
                            }
                        }
                    }
                }
                val opened = model.state.value.openedExam ?: error("PRIVATE_PICKER_IMPORT_FAILED")
                opened.entry.id to opened.extraction
            } else {
                val grant = target.contentResolver.call(
                    ExamContractProvider.uri(instrumentation.context, "grant"),
                    "authorize-example",
                    null,
                    null,
                )
                val source = requireNotNull(grant?.getParcelable("source", Uri::class.java))
                emit("authorized-stream-ready")
                val id = store.accept(source)
                val inference = LocalTextInference(target)
                val measured = if (args.getString("ownerDiagnostics") == "true") diagnostic(inference, ::emit) else inference
                id to ExamRunner(store, measured).extract(
                    id = id,
                    pages = page?.let { it..it },
                    onProgress = { p, total, block, blocks ->
                        emit("processing", JSONObject().put("page", p).put("pages", total).put("block", block).put("blocks", blocks))
                    },
                )
            }
            if (args.getString("compareChecklist") == "true") {
                val checklistGrant = target.contentResolver.call(
                    ExamContractProvider.uri(instrumentation.context, "grant"),
                    "authorize-checklist",
                    null,
                    null,
                )
                val checklist = requireNotNull(checklistGrant?.getParcelable("source", Uri::class.java))
                emit("authorized-checklist-ready")
                val comparison = requireNotNull(target.contentResolver.openInputStream(checklist)).use {
                    PrivateExamComparison.compare(it, result)
                }
                emit("authorized-comparison-measured", JSONObject().put("pages", comparison))
            }
            if (usePicker) {
                inspectPickerResult(activity, store, id, result, keepReview)
                emit(
                    "authorized-picker-controls",
                    JSONObject()
                        .put("sourceHighlight", result.pages.any { it.observations.isNotEmpty() })
                        .put("replacementPickerCancel", true)
                        .put("discardedThroughInterface", !keepReview),
                )
            } else if (keepReview) {
                store.save(id, result)
            }
            retained = keepReview
            emit(
                "authorized-extraction-measured",
                JSONObject()
                    .put("availableForDeviceReview", retained)
                    .put("partial", result.partial)
                    .put(
                        "pages",
                        JSONArray(
                            result.pages.map { outcome ->
                                JSONObject()
                                    .put("page", outcome.page)
                                    .put("observations", outcome.observations.size)
                                    .put("references", outcome.observations.count { it.reference != null })
                                    .put("referenceNeedsReview", outcome.observations.count { it.referenceNeedsReview })
                                    .put("issues", JSONArray(outcome.issues.map { it.name }))
                                    .put("inferenceFailures", JSONArray(outcome.inferenceFailures.map { it.name }))
                            },
                        ),
                    ),
            )
        } finally {
            try {
                if (!retained) withContext(NonCancellable) { store.deleteAll() }
            } finally {
                activity.close()
            }
        }
    }

    /** The final response stays in the test process. Only fixed shape labels and counts leave it. */
    private fun diagnostic(inference: TextInference, emit: (String, JSONObject) -> Unit): TextInference =
        object : TextInference by inference {
            override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
                val result = inference.generate(mode, request)
                if (result is GenerationResult.UnvalidatedFinal) {
                    val raw = result.text.trim()
                    val fenced = raw.startsWith("```") && raw.endsWith("```")
                    val body = if (fenced) raw.substringAfter(10.toChar()).substringBeforeLast("```").trim() else raw
                    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull()
                    val objectRoot = root as? JsonObject
                    val items = (objectRoot?.get("items") ?: objectRoot?.get("rows")) as? JsonArray
                    val isVision = request.imageBytes != null
                    val expectedKeys = if (isVision) setOf("name", "result", "unit", "reference", "needs_review")
                    else setOf("n", "v", "r", "u", "f")
                    val matchingRows = items.orEmpty().filterIsInstance<JsonObject>()
                    val validKeyCount = if (isVision) {
                        matchingRows.count { it.keys.all { k -> k in expectedKeys } && it.keys.containsAll(setOf("name", "result")) }
                    } else {
                        matchingRows.count { it.keys == expectedKeys }
                    }
                    emit(
                        "authorized-response-structure",
                        JSONObject()
                            .put("imageBytes", request.imageBytes?.size ?: 0)
                            .put("characters", raw.length)
                            .put("fenced", fenced)
                            .put("startsObject", raw.startsWith('{'))
                            .put("endsObject", raw.endsWith('}'))
                            .put("objectJson", objectRoot != null)
                            .put("arrayJson", root is JsonArray)
                            .put(
                                "rootKeysMatch",
                                objectRoot?.keys == (if (isVision) setOf("schema", "items") else setOf("schema", "rows")),
                            )
                            .put("versionMatches", (objectRoot?.get("schema") as? JsonPrimitive)?.content == request.schemaVersion)
                            .put("items", items?.size ?: -1)
                            .put("itemsWithExpectedKeys", validKeyCount)
                            .put("loadMs", result.loadMillis)
                            .put("generationMs", result.generationMillis)
                            .put("sampledMaxPssKiB", result.measurements?.sampledMaxPssKiB ?: -1L),
                    )
                }
                return result
            }
        }

    private fun viewModel(activity: ActivityScenario<MainActivity>): ExamViewModel {
        lateinit var model: ExamViewModel
        activity.onActivity { model = ViewModelProvider(it)[ExamViewModel::class.java] }
        return model
    }

    private suspend fun inspectPickerResult(
        activity: ActivityScenario<MainActivity>,
        store: EncryptedExamHistoryStore,
        id: String,
        result: ExamExtraction,
        keep: Boolean,
    ) {
        check(store.list().any { it.id == id })
        if (result.pages.any { it.observations.isNotEmpty() }) {
            ui("PRIVATE_SOURCE_PREVIEW_FAILED") {
                selectResultAction(hasText("Ver no laudo"))
                val crop = hasContentDescription("Recorte da página ", substring = true) and
                    hasContentDescription(" com destaque nos trechos confirmados", substring = true)
                compose.waitUntil(15_000) { compose.onAllNodes(crop).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Página inteira").performSemanticsAction(SemanticsActions.OnClick)
                val fullPage = hasContentDescription(" inteira com destaque nos trechos confirmados", substring = true)
                compose.waitUntil(15_000) { compose.onAllNodes(fullPage).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Voltar aos resultados", substring = false).performSemanticsAction(SemanticsActions.OnClick)
            }
        }
        if (result.pages.any { page -> page.observations.any { it.referenceNeedsReview } }) {
            ui("PRIVATE_UNCERTAINTY_NOTICE_NOT_VISIBLE") {
                val notice = hasText("Confira a referência no laudo.", substring = true)
                if (compose.onAllNodes(notice).fetchSemanticsNodes().isEmpty()) {
                    compose.onNode(hasScrollToNodeAction()).performScrollToNode(notice)
                }
                check(compose.onAllNodes(notice).fetchSemanticsNodes().isNotEmpty())
            }
        }
        ui("PRIVATE_REPLACEMENT_PICKER_FAILED") {
            selectResultAction(hasText("Selecionar outro PDF"))
            val picker = ExamPickerAutomation(InstrumentationRegistry.getInstrumentation().uiAutomation)
            picker.waitForPicker()
            picker.back()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Selecionar outro PDF")).fetchSemanticsNodes().isNotEmpty()
            }
        }
        check(viewModel(activity).state.value.openedExam?.entry?.id == id)
        if (!keep) {
            ui("PRIVATE_DISCARD_FROM_RESULTS_FAILED") {
                selectResultAction(hasText("Descartar extração"))
                compose.onNodeWithText("Descartar", substring = false).performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasText("Extração descartada.")).fetchSemanticsNodes().isNotEmpty()
                }
            }
            check(store.list().none { it.id == id })
        }
    }

    private fun selectResultAction(action: SemanticsMatcher) {
        if (compose.onAllNodes(action).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(action)
        }
        compose.onAllNodes(action)[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun ui(code: String, action: () -> Unit) {
        try {
            action()
        } catch (_: AssertionError) {
            error(code)
        } catch (_: Exception) {
            error(code)
        }
    }
}
