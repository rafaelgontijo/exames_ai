package br.com.exames.app

import android.app.UiAutomation
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.*
import br.com.exames.domain.validation.*
import br.com.exames.featureimport.ImportViewModel
import br.com.exames.featureresults.ResultsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class CorpusWorkbenchTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun validatedPackageRunsOfflineAndReportIsAccessibleAtLargeFont() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("workbenchCorpus")=="true")
        val context=instrumentation.targetContext
        assertEquals("br.com.exames.offline.validation.workbench",context.packageName)
        val accessibility=InstrumentationRegistry.getArguments().getString("workbenchAccessibility")=="true"
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if(accessibility) {
            assertTrue(context.resources.configuration.fontScale>=1.9f)
            val manager=context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            assertTrue(manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN)
                .any { it.id.contains("com.google.android.marvin.talkback") })
        }
        lateinit var importer: ImportViewModel;lateinit var results: ResultsViewModel
        lateinit var history: br.com.exames.featurehistory.HistoryViewModel
        compose.runOnIdle {
            importer=ViewModelProvider(compose.activity)[ImportViewModel::class.java]
            results=ViewModelProvider(compose.activity)[ResultsViewModel::class.java]
            history=ViewModelProvider(compose.activity)[br.com.exames.featurehistory.HistoryViewModel::class.java]
        }
        compose.openSend()
        compose.onNodeWithText("Importar pacote do corpus").performScrollTo().assertIsDisplayed()
        context.contentResolver.call(WorkbenchSyntheticProvider.URI,"allow",context.packageName,null)
        compose.runOnIdle { importer.selectPackage(WorkbenchSyntheticProvider.CORPUS_URI) }
        compose.waitUntil(90000) { !importer.state.value.importing }
        assertEquals("SYNTHETIC_CORPUS_CASE_COUNT",8,importer.state.value.importedCases)
        context.contentResolver.call(WorkbenchSyntheticProvider.URI,"revoke",null,null)
        val factory=ProtectedDatabaseFactory(context);val repo=ProtectedValidationRepository(factory)
        try {
            val documents=runBlocking { repo.observeDocuments().first().filter { it.corpusCase!=null } }
            assertEquals(8,documents.size)
            val runs=runBlocking { documents.map { repo.observeRuns(it.id).first().single() } }
            compose.waitUntil(90000) { runBlocking { runs.all { repo.findRun(it.runId)?.state !in setOf(RunState.QUEUED,RunState.RUNNING) } } }
            runBlocking {
                assertEquals(CorpusFormat.entries.toSet(),documents.map { it.corpusCase!!.format }.toSet())
                assertEquals(2,documents.map { it.corpusCase!!.layoutId }.distinct().size)
                for(run in runs) {
                    val document=documents.single { it.id==run.documentId }
                    assertEquals(document.corpusCase!!.caseId,run.caseId)
                    assertEquals(document.corpusCase!!.groundTruthSha256,repo.groundTruth(document.id).single().sha256)
                    val completed=repo.findRun(run.runId)!!.state==RunState.COMPLETE
                    if(completed) assertEquals(5,repo.observations(run.runId).size)
                    else {
                        assertEquals(RunState.FAILED,repo.findRun(run.runId)!!.state)
                        assertEquals("PAGE_UNSUPPORTED_LAYOUT",repo.checkpoints(run.runId).single().errorCode)
                        assertTrue(repo.observations(run.runId).isEmpty())
                    }
                    assertNotNull(repo.checkpoints(run.runId).single().measurements)
                }
                val successful=runs.filter { repo.findRun(it.runId)!!.state==RunState.COMPLETE }.map { run -> documents.single { it.id==run.documentId } }
                assertEquals(CorpusFormat.entries.toSet(),successful.map { it.corpusCase!!.format }.toSet())
                assertEquals(2,successful.map { it.corpusCase!!.layoutId }.distinct().size)
            }
            compose.onNodeWithText("Revisar resultados").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.waitUntil(15000) { !results.state.value.loading && results.state.value.run?.runId==importer.state.value.runId && results.state.value.corpusCase!=null }
            val hash=results.state.value.groundTruth.single().sha256
            compose.onNodeWithTag("results-list").performScrollToNode(hasTestTag("report-$hash"))
            compose.onNodeWithTag("report-$hash").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.waitUntil(15000) { results.state.value.report!=null }
            compose.onNodeWithTag("validation-report").assertIsDisplayed()
            compose.onNodeWithText("Relatório local").assertIsDisplayed()
            compose.onNodeWithText("Fechar relatório").assertHeightIsAtLeast(48.dp)
            if(accessibility) {
                fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if(node==null) return null
                    if(node.text?.toString()=="Relatório local") return node
                    repeat(node.childCount) { find(node.getChild(it))?.let { found -> return found } }
                    return null
                }
                val title=requireNotNull(find(automation.rootInActiveWindow))
                assertTrue(title.isHeading)
                title.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                val event=automation.executeAndWaitForEvent({ assertTrue(title.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) },
                    { it.eventType==AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED },5000)
                assertEquals(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,event.eventType)
            }
            val screenshot=automation.takeScreenshot()
            val png=ByteArrayOutputStream();assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,png));screenshot.recycle()
            instrumentation.sendStatus(0,Bundle().apply {
                putString("corpusScreenshot",android.util.Base64.encodeToString(png.toByteArray(),android.util.Base64.NO_WRAP))
            })
            compose.onNodeWithTag("validation-report").performScrollToNode(hasText("Acerto ponta a ponta:",substring=true))
            compose.onNodeWithTag("validation-report").performScrollToNode(hasText("Fechar relatório"))
            compose.onNodeWithText("Fechar relatório").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            assertNull(results.state.value.report)
            for(document in documents) {
                compose.runOnIdle { history.deleteDocument(document.id) }
                compose.waitUntil(15000) { !history.state.value.actionBusy }
            }
            compose.waitUntil(15000) { results.state.value.unavailable }
            assertNull(results.state.value.report)
            instrumentation.sendStatus(0,Bundle().apply {
                putInt("corpusCases",8);putBoolean("corpusPassed",true);putBoolean("talkBackChecked",accessibility)
                putFloat("fontScaleChecked",context.resources.configuration.fontScale)
            })
        } finally { runBlocking { factory.open().close() } }
    }
    @Test fun inspectSyntheticCorpusProcessingFailures() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("corpusDiagnostic")=="true")
        val context=instrumentation.targetContext;assertEquals("br.com.exames.offline.validation.workbench",context.packageName)
        val factory=ProtectedDatabaseFactory(context);val repo=ProtectedValidationRepository(factory)
        val artifacts=EncryptedArtifactStore(context)
        val pdf=br.com.exames.datadocuments.PdfPreviewRenderer(context,LocalDataModule.pdfSource(artifacts,repo))
        val loader=PrivatePageLoader(repo,artifacts,pdf)
        try {
            val docs=repo.observeDocuments().first().filter { it.corpusCase?.origin==CorpusOrigin.SYNTHETIC }
            val summary=org.json.JSONArray()
            for(d in docs) {
                val r=repo.observeRuns(d.id).first().single();val checkpoints=repo.checkpoints(r.runId)
                val row=org.json.JSONObject().put("format",d.corpusCase!!.format.name).put("layout",d.corpusCase!!.layoutId)
                    .put("state",r.state.name).put("errors",checkpoints.map { it.errorCode }.joinToString(","))
                    .put("warnings",checkpoints.flatMap { it.parseWarnings }.joinToString(","))
                try { loader.load(d.id,1).use { page ->
                    val parsed=br.com.exames.domain.parser.BiochemistryParser().parse(d.id,r.runId,1,page.text.fragments)
                    row.put("fragments",page.text.fragments.size).put("detectedLayout",parsed.layout?.name).put("observations",parsed.observations.size)
                    val allowed=setOf("analito","resultado","unidade","referência","referencia","layout:","simple-v1","footnote-v1")
                    row.put("headers",org.json.JSONArray(page.text.fragments.filter { it.raw.trim().lowercase() in allowed }.map { it.raw }))
                    row.put("frame", "${page.bitmap.width}x${page.bitmap.height}")
                } } catch(failure: Exception) { row.put("decodeFailureType",failure.javaClass.simpleName) }
                summary.put(row)
            }
            instrumentation.sendStatus(0,Bundle().apply { putString("corpusDiagnostics",summary.toString()) })
        } finally { factory.open().close() }
    }

}
