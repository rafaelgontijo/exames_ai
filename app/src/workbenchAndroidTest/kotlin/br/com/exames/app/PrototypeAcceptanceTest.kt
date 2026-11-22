package br.com.exames.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.domain.inference.ModelReadiness
import br.com.exames.domain.model.AssessmentStatus
import br.com.exames.domain.summary.*
import br.com.exames.domain.validation.*
import br.com.exames.featureimport.ImportViewModel
import br.com.exames.featureresults.ResultsViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest

/** Disposable package only. Synthetic provider/recipient live under a different UID. */
class PrototypeAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val resolver get() = context.contentResolver

    @Test fun multipageBaselineAndLocalRecipient() = checkReport(ExtractionMode.BASELINE)
    @Test fun multipageAssistedAndLocalRecipient() {
        assumeTrue(android.os.Build.MODEL == "SM-S931B")
        checkReport(ExtractionMode.ASSISTED)
    }

    @Test fun audiblePlaybackAndStopOnReferenceDevice() {
        assumeTrue(context.packageName == SyntheticSummaryReceiver.SOURCE && android.os.Build.MODEL == "SM-S931B")
        openSyntheticResult()
        compose.onNodeWithTag("results-list").performScrollToNode(hasText("Ouvir resumo"))
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Ouvir resumo")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Ouvir resumo").performScrollTo()
        event(JSONObject().put("phase", "speech-ready"))
        compose.waitUntil(120_000) {
            compose.onAllNodes(hasText("Parar leitura")).fetchSemanticsNodes().isNotEmpty()
        }
        event(JSONObject().put("phase", "speech-started"))
        compose.waitUntil(90_000) {
            compose.onAllNodes(hasText("Ouvir resumo")).fetchSemanticsNodes().isNotEmpty()
        }
        event(JSONObject().put("phase", "speech-returned-to-idle"))
    }

    @Test fun deletingAnActiveSummaryStopsSpeechAndClosesPrivateExport() {
        assumeTrue(context.packageName == SyntheticSummaryReceiver.SOURCE && android.os.Build.MODEL == "SM-S931B")
        val (history, results) = openSyntheticResult()
        val state = results.state.value
        val run = requireNotNull(state.run)
        val weights = java.io.File(context.noBackupFilesDir, "models/gemma4.litertlm")
        val modelHash = weights.inputStream().use(::sha256)
        val store = compose.activity.shareStore
        val text = "Laudo sintético de teste. ".repeat(2400)
        val rendered = SummaryTextResult.Rendered(text, text.toByteArray().size, 0, "synthetic-stalled-reader")
        val location = runBlocking { store.prepare(run.documentId, run.runId, rendered) }
        val reader = requireNotNull(resolver.openFileDescriptor(location, "r"))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(reader).use { stream ->
            compose.onNodeWithTag("results-list").performScrollToNode(hasText("Ouvir resumo"))
            compose.onNodeWithText("Ouvir resumo").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Parar leitura").assertExists()
            compose.runOnIdle { history.deleteDocument(run.documentId) }
            compose.waitUntil(15_000) { results.state.value.unavailable }
            compose.onNodeWithText("Parar leitura").assertDoesNotExist()
            try {
                resolver.openInputStream(location)?.close()
                fail("REVOKED_EXPORT_REOPENED")
            } catch (_: java.io.FileNotFoundException) { }
            // Bytes already written into the pipe may still be readable after deletion.
            assertTrue(stream.readBytes().size <= rendered.utf8Bytes)
        }
        val directory = java.io.File(context.noBackupFilesDir, "artifacts/${run.documentId}")
        compose.waitUntil(30_000) { !directory.exists() }
        assertEquals(modelHash, weights.inputStream().use(::sha256))
        event(JSONObject().put("phase", "delete-during-speech-and-share").put("staticWeightsPreserved", true))
    }

    @Test fun scannedPdfAndImagesOpenFromReferenceDevicePicker() {
        assumeTrue(context.packageName == SyntheticSummaryReceiver.SOURCE && android.os.Build.MODEL == "SM-S931B")
        compose.openSend()
        val importer = compose.runOnIdle { ViewModelProvider(compose.activity)[ImportViewModel::class.java] }
        val cases = listOf(Triple("laudo-misto-sintetico.pdf", "application/pdf", 3),
            Triple("imagem-sintetica.png", "image/png", 1), Triple("imagem-sintetica.jpg", "image/jpeg", 1))
        for ((label, mime, pages) in cases) {
            val previous = importer.state.value.runId
            compose.onNodeWithText("Selecionar documento").performScrollTo().performClick()
            selectSyntheticPdf(label)
            compose.waitUntil(90_000) {
                val state = importer.state.value
                state.runId != null && state.runId != previous && state.runState in setOf(RunState.COMPLETE, RunState.PARTIAL, RunState.FAILED)
            }
            assertEquals(pages, importer.state.value.totalPages)
            compose.onNodeWithText("Revisar resultados").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            val results = compose.runOnIdle { ViewModelProvider(compose.activity)[ResultsViewModel::class.java] }
            compose.waitUntil(15_000) { results.state.value.run?.runId == importer.state.value.runId }
            assertEquals(pages, results.state.value.pageCount)
            for (page in 1..pages) {
                compose.runOnIdle { results.showPage(page) }
                compose.waitUntil(15_000) { !results.state.value.previewBusy && results.state.value.preview != null }
                assertEquals(page, results.state.value.previewPage)
            }
            event(JSONObject().put("phase", "picker-format-opened").put("mime", mime).put("pages", pages))
            compose.onNodeWithTag("results-list").performScrollToNode(hasText("Voltar"))
            compose.onNodeWithText("Voltar").performSemanticsAction(SemanticsActions.OnClick)
        }
    }

    @Test fun importExplicitlyAuthorizedOwnerPdf() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownerApproved") == "true")
        assertEquals(SyntheticSummaryReceiver.SOURCE, context.packageName)
        assertEquals("SM-S931B", android.os.Build.MODEL)
        assertEquals(1, android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.AIRPLANE_MODE_ON))
        assertEquals(0, android.provider.Settings.Global.getInt(resolver, "wifi_on"))
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[ImportViewModel::class.java] }
        val mode = if (InstrumentationRegistry.getArguments().getString("ownerMode") == "ASSISTED")
            ExtractionMode.ASSISTED else ExtractionMode.BASELINE
        if (mode == ExtractionMode.ASSISTED) {
            compose.runOnIdle { app.selectMode(mode) }
            compose.waitUntil(240_000) { app.state.value.modelReadiness != ModelReadiness.PREPARING }
            assertEquals(ModelReadiness.READY, app.state.value.modelReadiness)
        }
        val granted = requireNotNull(resolver.call(WorkbenchSyntheticProvider.URI, "prototype-authorize-example", null, null))
        val source = requireNotNull(granted.getParcelable("source", android.net.Uri::class.java))
        event(JSONObject().put("phase", "authorized-stream-ready"))
        compose.runOnIdle { app.select(source) }
        compose.waitUntil(480_000) {
            val state = app.state.value
            !state.importing && (state.runState in setOf(RunState.COMPLETE, RunState.PARTIAL, RunState.FAILED) ||
                (state.runId == null && state.message != null))
        }
        val factory = br.com.exames.datalocal.ProtectedDatabaseFactory(context)
        runBlocking {
            try {
                val repo = br.com.exames.datalocal.ProtectedValidationRepository(factory)
                val state = app.state.value
                val run = state.runId?.let { repo.findRun(it) }
                val checks = run?.let { repo.checkpoints(it.runId) }.orEmpty()
                val counts = run?.let { repo.assessments(it.runId) }.orEmpty().groupingBy { it.status.name }.eachCount()
                event(JSONObject().put("phase", "authorized-import-processed").put("mode", mode.name)
                    .put("run", run?.state?.name ?: "IMPORT_UNAVAILABLE")
                    .put("pages", state.totalPages ?: 0).put("completed", state.completedPages)
                    .put("failed", state.failedPages).put("assessmentCounts", JSONObject(counts))
                    .put("pageErrors", org.json.JSONArray(checks.mapNotNull { it.errorCode }))
                    .put("fallbackPages", checks.count { it.inferenceOutcome == InferenceOutcome.FALLBACK }))
            } finally { factory.open().close() }
        }
    }

    @Test fun verifyCameraFixtureOnReferenceDevice() = runBlocking {
        assumeTrue(context.packageName == SyntheticSummaryReceiver.SOURCE && android.os.Build.MODEL == "SM-S931B")
        val factory = br.com.exames.datalocal.ProtectedDatabaseFactory(context)
        try {
            val repo = br.com.exames.datalocal.ProtectedValidationRepository(factory)
            val store = br.com.exames.datalocal.EncryptedArtifactStore(context)
            // The small synthetic JPEG from SAF is distinct from the CameraX frame.
            val (document, decoded) = repo.observeDocuments().first().asSequence()
                .filter { it.mimeType == "image/jpeg" }.map { document ->
                    val image = store.read(document.id, "original")
                    val geometry = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    try { android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size, geometry) }
                    finally { image.fill(0) }
                    document to geometry
                }.first { (_, geometry) -> maxOf(geometry.outWidth, geometry.outHeight) >= 1000 }
            val run = repo.observeRuns(document.id).first().first()
            val checkpoints = repo.checkpoints(run.runId)
            val observations = repo.observations(run.runId)
            val assessments = repo.assessments(run.runId)
            val pdf = br.com.exames.datadocuments.PdfPreviewRenderer(context,
                br.com.exames.datalocal.LocalDataModule.pdfSource(store, repo))
            val loader = br.com.exames.datalocal.PrivatePageLoader(repo, store, pdf)
            val ocr = loader.load(document.id, 1).use { it.text.fragments.map { row -> row.raw.lowercase() } }
            val headers = listOf("analito", "resultado", "unidade", "referência")
            val headersSeen = headers.filter { key -> ocr.any { it.contains(key) } }
            event(JSONObject().put("phase", "camera-fixture").put("run", run.state.name)
                .put("mime", document.mimeType).put("width", decoded.outWidth).put("height", decoded.outHeight)
                .put("pages", document.pageCount).put("observations", observations.size)
                .put("ocrSpans", ocr.size).put("tableHeadersSeen", org.json.JSONArray(headersSeen))
                .put("pageErrors", org.json.JSONArray(checkpoints.mapNotNull { it.errorCode }))
                .put("statuses", JSONObject(assessments.groupingBy { it.status.name }.eachCount())))
            assertEquals(1, document.pageCount)
            assertTrue(decoded.outWidth > 0 && decoded.outHeight > 0)
            assertTrue(observations.isNotEmpty())
            assertTrue(observations.flatMap { it.fieldEvidence }.any { it.method.contains("OCR") && it.region.page == 1 })
        } finally { factory.open().close() }
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openSyntheticResult(): Pair<br.com.exames.featurehistory.HistoryViewModel, ResultsViewModel> {
        compose.openSend()
        compose.onNodeWithText("Histórico local").performScrollTo().performClick()
        val history = compose.runOnIdle {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ViewModelProvider(compose.activity)[br.com.exames.featurehistory.HistoryViewModel::class.java]
        }
        compose.waitUntil(15_000) { history.state.value.documents.isNotEmpty() }
        val fixtureDigest = instrument.context.assets.open("source.pdf").use(::sha256)
        val document = history.state.value.documents.first { it.hash == fixtureDigest }
        compose.runOnIdle { history.selectDocument(document.id) }
        compose.waitUntil(15_000) { history.state.value.runs.isNotEmpty() }
        val run = history.state.value.runs.first()
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("run-${run.runId}"))
        compose.onNode(hasText("Abrir resultado e fonte") and hasAnyAncestor(hasTestTag("run-${run.runId}")))
            .performSemanticsAction(SemanticsActions.OnClick)
        val results = compose.runOnIdle { ViewModelProvider(compose.activity)[ResultsViewModel::class.java] }
        compose.waitUntil(15_000) { results.state.value.run?.runId == run.runId && !results.state.value.loading }
        return history to results
    }

    private fun checkReport(mode: ExtractionMode) {
        assumeTrue(context.packageName == SyntheticSummaryReceiver.SOURCE)
        val truth = JSONObject(instrument.context.assets.open("expected.json").bufferedReader().use { it.readText() })
        val expected = truth.getJSONArray("pages")
        val marker = truth.getString("forbiddenSummaryMarker")
        fun control(action: String, arg: String? = null) = requireNotNull(resolver.call(
            WorkbenchSyntheticProvider.URI, "prototype-$action", arg, null))
        control("reset")
        compose.openSend()
        val importer = compose.runOnIdle { ViewModelProvider(compose.activity)[ImportViewModel::class.java] }
        if (mode == ExtractionMode.ASSISTED) {
            compose.runOnIdle { importer.selectMode(mode) }
            compose.waitUntil(240_000) { importer.state.value.modelReadiness != ModelReadiness.PREPARING }
            assertEquals(ModelReadiness.READY, importer.state.value.modelReadiness)
        }
        val realPicker = android.os.Build.MODEL == "SM-S931B"
        if (realPicker) {
            compose.onNodeWithText("Selecionar documento").performScrollTo().performClick()
            selectSyntheticPdf()
        } else {
            compose.runOnIdle { importer.select(WorkbenchSyntheticProvider.MULTIPAGE_URI) }
        }
        compose.waitUntil(480_000) { importer.state.value.runState in setOf(RunState.COMPLETE, RunState.PARTIAL, RunState.FAILED) }
        compose.onNodeWithText("Revisar resultados").performScrollTo().performClick()
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[ResultsViewModel::class.java] }
        compose.waitUntil(30_000) { vm.state.value.run?.runId == importer.state.value.runId && vm.state.value.rows.isNotEmpty() }
        val state = vm.state.value
        val run = requireNotNull(state.run)
        assertEquals(mode, run.mode)
        assertEquals(6, state.pageCount)
        assertEquals((1..6).toSet(), state.checkpoints.map { it.page }.toSet())
        val expectedRows = (0 until expected.length()).map(expected::getJSONObject)
        val observed = state.rows.associateBy { it.observation.page to it.observation.analyteNormalized }
        val desired = expectedRows.filter { it.has("analyte") }
        val keys: Set<Pair<Int, String?>> = desired.map { it.getInt("page") to it.getString("analyte") }.toSet()
        event(JSONObject().put("phase", "six-pages").put("mode", mode.name)
            .put("actualPicker", realPicker).put("missing", (keys - observed.keys).size).put("extra", (observed.keys - keys).size)
            .put("fallbackPages", state.checkpoints.count { it.inferenceOutcome == InferenceOutcome.FALLBACK })
            .put("statuses", JSONObject(state.rows.mapNotNull { it.assessment }.groupingBy { it.status.name }.eachCount()))
            .put("pageErrors", org.json.JSONArray(state.checkpoints.mapNotNull { it.errorCode })))
        assertEquals(keys, observed.keys)
        assertEquals(keys.size, state.rows.size)
        assertTrue(state.checkpoints.filter { it.state == PageState.FAILED }.all {
            it.errorCode == "PAGE_NO_SUPPORTED_OBSERVATIONS" && it.page in setOf(2, 5)
        })
        for (block in desired) {
            val page = block.getInt("page")
            val row = observed.getValue(page to block.getString("analyte"))
            assertEquals(block.getString("value"), row.observation.resultRaw)
            assertEquals(block.getString("unit"), row.observation.unitRaw)
            assertEquals(if (block.isNull("reference")) null else block.getString("reference"), row.observation.referenceRaw)
            // Assisted conflict may conservatively add review to a direct result.
            if (mode == ExtractionMode.BASELINE || row.assessment?.status != AssessmentStatus.NEEDS_REVIEW)
                assertEquals(block.getString("status"), row.assessment?.status?.name)
            assertTrue(row.observation.fieldEvidence.isNotEmpty())
            assertTrue(row.observation.fieldEvidence.all { it.region.page == page })
            compose.runOnIdle { vm.showEvidence(row.observation.id, "resultRaw", page) }
            compose.waitUntil(15_000) { !vm.state.value.previewBusy && vm.state.value.preview != null }
            assertEquals(page, vm.state.value.previewPage)
            assertEquals(block.getString("value"), vm.state.value.selectedEvidence?.raw)
        }
        for (page in listOf(2, 5)) {
            compose.runOnIdle { vm.showPage(page) }
            compose.waitUntil(15_000) { !vm.state.value.previewBusy && vm.state.value.preview != null }
            assertEquals(page, vm.state.value.previewPage)
            assertNull(vm.state.value.selectedEvidence)
        }
        compose.runOnIdle { vm.closePreview() }
        val snapshot = SummaryText.render(ProjectReferenceSummary.project(run, state.rows.map { it.observation },
            state.rows.mapNotNull { it.assessment }, state.checkpoints, state.pageCount)) as SummaryTextResult.Rendered
        assertFalse(snapshot.text.contains(marker))
        fun sharePreview() {
            compose.onNodeWithTag("results-list").performScrollToNode(hasText("Compartilhar resumo"))
            compose.onNodeWithText("Compartilhar resumo").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText(snapshot.text).assertExists()
        }
        sharePreview()
        if (InstrumentationRegistry.getArguments().getString("captureSynthetic") == "true") {
            compose.waitForIdle()
            instrument.uiAutomation.waitForIdle(600, 5000)
            val image = requireNotNull(instrument.uiAutomation.takeScreenshot())
            try {
                java.io.File(context.cacheDir, "prototype-synthetic-preview.png").outputStream().use {
                    check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { image.recycle() }
        }
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals("", control("delivery").getString("digest"))

        // Check denial from the receiver process before it receives a read grant.
        val store = compose.activity.shareStore
        val uri = runBlocking { store.prepare(run.documentId, run.runId, snapshot) }
        try {
            assertFalse(control("probe", uri.toString()).getBoolean("r"))
            context.grantUriPermission(instrument.context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val probe = control("probe", uri.toString())
            assertTrue(probe.getBoolean("r"))
            assertFalse(probe.getBoolean("w"))
        } finally { runBlocking { store.revoke(uri) } }
        assertFalse(control("probe", uri.toString()).getBoolean("r"))

        sharePreview()
        compose.onNodeWithText("Escolher aplicativo").performClick()
        chooseReceiver()
        val sha = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(snapshot.text.toByteArray()), Base64.NO_WRAP)
        compose.waitUntil(20_000) { control("delivery").getString("digest") == sha }
        event(JSONObject().put("phase", "exact-delivery").put("mode", mode.name).put("matched", true))
    }

    private fun event(value: JSONObject) {
        instrument.sendStatus(2, Bundle().apply { putString("prototype", value.toString()) })
    }

    private fun clickSystemLabel(root: AccessibilityNodeInfo, label: String, onlyExactText: Boolean = false): Boolean {
        for (node in root.findAccessibilityNodeInfosByText(label)) {
            if (!node.isVisibleToUser || (onlyExactText && node.text?.toString() != label)) continue
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            // API 24 exposes root list rows as non-clickable even though the list handles their taps.
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            if (!bounds.isEmpty) {
                val down = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val tap = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    tap.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { instrument.uiAutomation.injectInputEvent(tap, true) } finally { tap.recycle() }
                }
                return true
            }
        }
        return false
    }

    private fun selectSyntheticPdf(label: String = "laudo-seis-paginas.pdf") {
        val until = SystemClock.elapsedRealtime() + 120_000
        var openedDrawer = false
        var selectedRoot = false
        while (SystemClock.elapsedRealtime() < until) {
            val root = instrument.uiAutomation.rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (pkg == context.packageName && selectedRoot) return
            if (root != null && pkg?.contains("documentsui") == true) {
                if (clickSystemLabel(root, label, onlyExactText = true)) {
                    selectedRoot = true
                    SystemClock.sleep(1500)
                }
                else if (!selectedRoot && clickSystemLabel(root, "Laudos sintéticos")) selectedRoot = true
                else if (!openedDrawer) openedDrawer = listOf("Show roots", "Mostrar raízes", "Mostrar locais",
                    "Mostrar pastas", "Open navigation drawer").any { clickSystemLabel(root, it) }
            }
            SystemClock.sleep(400)
        }
        fail("SAF_SYNTHETIC_SELECTION_FAILED")
    }

    private fun chooseReceiver() {
        val automation = instrument.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val until = SystemClock.elapsedRealtime() + 70_000
        while (SystemClock.elapsedRealtime() < until) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                if (clickSystemLabel(root, "Leitor sintético local", onlyExactText = true)) return
                val isSharesheet = root.packageName?.toString() in setOf("android", "com.android.intentresolver")
                if (isSharesheet &&
                    !listOf("Mais", "More").any { clickSystemLabel(root, it, onlyExactText = true) }) {
                    val rows = root.findAccessibilityNodeInfosByViewId("com.android.intentresolver:id/sem_chooser_row_recycler_view")
                    rows.lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }
            SystemClock.sleep(450)
        }
        fail("SYNTHETIC_RECEIVER_NOT_VISIBLE_IN_CHOOSER")
    }
}
