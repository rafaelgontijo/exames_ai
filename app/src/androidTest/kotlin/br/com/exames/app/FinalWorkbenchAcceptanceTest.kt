package br.com.exames.app

import android.os.Bundle
import android.os.Process
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.*
import br.com.exames.domain.model.CorrectionField
import br.com.exames.domain.inference.ModelReadiness
import br.com.exames.domain.validation.*
import br.com.exames.featurehistory.HistoryViewModel
import br.com.exames.featureimport.ImportViewModel
import br.com.exames.featureresults.ResultsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Host runs measure, force-stop, resume. Only a previously absent disposable package is permitted. */
class FinalWorkbenchAcceptanceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun realOfflinePairsSurviveRestartAndReviewWithoutChangingOriginalMetrics() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val phase=InstrumentationRegistry.getArguments().getString("finalPhase")
        assumeTrue(phase in setOf("measure","resume"))
        val context=instrumentation.targetContext
        assertEquals("br.com.exames.offline.validation.final",context.packageName)
        assertEquals("SM-S931B",android.os.Build.MODEL)
        assertTrue("PRODUCTION_PROTOCOL_NOT_ACCEPTED",br.com.exames.inferencelocal.SelectedInferenceConfiguration.PRODUCTION_PROTOCOL_ACCEPTED)
        assertFalse((context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked)
        lateinit var importer: ImportViewModel;lateinit var history: HistoryViewModel;lateinit var results: ResultsViewModel
        compose.runOnIdle {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            importer=ViewModelProvider(compose.activity)[ImportViewModel::class.java]
            history=ViewModelProvider(compose.activity)[HistoryViewModel::class.java]
            results=ViewModelProvider(compose.activity)[ResultsViewModel::class.java]
        }
        val factory=ProtectedDatabaseFactory(context);val repo=ProtectedValidationRepository(factory)
        val marker=File(context.noBackupFilesDir,"synthetic-final-ids.json")
        fun clickText(text: String)=compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        fun openRun(documentId: String,runId: String) {
            compose.onNodeWithText("Histórico local").performScrollTo();clickText("Histórico local")
            compose.runOnIdle { history.selectDocument(documentId) }
            compose.waitUntil(15000) { history.state.value.runs.any { it.runId==runId } }
            compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("run-$runId"))
            compose.onNode(hasText("Abrir resultado e fonte") and hasAnyAncestor(hasTestTag("run-$runId")))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.waitUntil(15000) { results.state.value.run?.runId==runId && !results.state.value.loading }
        }
        try {
            if(phase=="measure") {
                assertFalse(marker.exists())
                compose.onNodeWithText("Modo assistido local").performScrollTo().performClick()
                compose.waitUntil(180000) { importer.state.value.modelReadiness!=ModelReadiness.PREPARING }
                assertEquals(ModelReadiness.READY,importer.state.value.modelReadiness)
                compose.onNodeWithText("Modelo preparado nesta sessão.",substring=true).assertExists()
                compose.onNodeWithText("Modo sem IA").performScrollTo().performClick()
                assertEquals(ExtractionMode.BASELINE,importer.state.value.mode)
                event("model-prepared",JSONObject().put("ready",true).put("baselineSelectable",true))
                context.contentResolver.call(WorkbenchSyntheticProvider.URI,"allow",context.packageName,null)
                compose.runOnIdle { importer.selectPackage(WorkbenchSyntheticProvider.CORPUS_URI) }
                compose.waitUntil(90000) { !importer.state.value.importing }
                assertEquals(8,importer.state.value.importedCases)
                context.contentResolver.call(WorkbenchSyntheticProvider.URI,"revoke",null,null)
                val documents=runBlocking { repo.observeDocuments().first().filter { it.corpusCase!=null } }
                assertEquals(8,documents.size)
                assertTrue(documents.all { it.corpusCase!!.partition==CorpusPartition.DEVELOPMENT })
                val baseline=runBlocking { documents.associate { d -> d.id to repo.observeRuns(d.id).first().single() } }
                compose.waitUntil(120000) { runBlocking { baseline.values.all { repo.findRun(it.runId)?.state !in setOf(RunState.QUEUED,RunState.RUNNING) } } }
                val pairs=mutableListOf<PairedValidationReport>();val selections=mutableListOf<ReportSelection>()
                for(document in documents) {
                    event("assisted-start",JSONObject().put("caseId",document.corpusCase!!.caseId).put("format",document.corpusCase!!.format.name))
                    compose.runOnIdle { history.processAssisted(document.id) }
                    compose.waitUntil(180000) { !history.state.value.actionBusy }
                    val assisted=runBlocking { repo.observeRuns(document.id).first().single { it.mode==ExtractionMode.ASSISTED } }
                    compose.waitUntil(300000) { runBlocking { repo.findRun(assisted.runId)?.state !in setOf(RunState.QUEUED,RunState.RUNNING) } }
                    val hash=document.corpusCase!!.groundTruthSha256
                    val pair=runBlocking { CompareExtractionModes(repo).compare(baseline.getValue(document.id).runId,assisted.runId,hash) }
                    pairs+=pair
                    selections+=ReportSelection(document.corpusCase!!,baseline.getValue(document.id).runId,hash)
                    selections+=ReportSelection(document.corpusCase!!,assisted.runId,hash)
                    event("pair",JSONObject(ValidationJson.format.encodeToString(pair)))
                }
                assertTrue("FALLBACK_EXCLUSIVE_IS_NOT_AI_ACCEPTANCE",pairs.sumOf { it.assisted.acceptedModelPages }>0)
                val aggregate=runBlocking { GenerateLocalValidationReport(repo).generate(selections) }
                event("aggregate",JSONObject(ValidationJson.format.encodeToString(aggregate)))
                val selected=pairs.first { it.assisted.acceptedModelPages>0 }
                val selectedRun=runBlocking { requireNotNull(repo.findRun(selected.assisted.runId)) }
                val beforeRows=runBlocking { repo.observations(selectedRun.runId) }
                val beforeAssessments=runBlocking { repo.assessments(selectedRun.runId).associateBy { it.observationId } }
                val beforeComparisons=runBlocking { repo.comparisons(selectedRun.runId) }
                openRun(selectedRun.documentId,selectedRun.runId)
                compose.onNodeWithText("IA executada:",substring=true).assertExists()
                val row=results.state.value.rows.first()
                val tag="correct-${row.observation.id}-RESULT"
                compose.onNodeWithTag("results-list").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                compose.onNodeWithTag("review-draft").performTextReplacement("25")
                compose.onNodeWithTag("review-save").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                compose.waitUntil(15000) {
                    val state=results.state.value
                    !state.reviewBusy && !state.loading && state.draft==null && state.run?.runId==selectedRun.runId &&
                        state.rows.any { saved -> saved.observation.id==row.observation.id && saved.corrections.any {
                            it.field==CorrectionField.RESULT && it.newValue=="25"
                        } }
                }
                compose.onNodeWithTag("results-list").performScrollToNode(hasTestTag("reevaluate"))
                compose.onNodeWithTag("reevaluate").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                compose.waitUntil(15000) { results.state.value.run?.outputBasis==OutputBasis.CORRECTED }
                val child=requireNotNull(results.state.value.run)
                compose.onNodeWithText("Esta reavaliação não executou novamente o modelo.").assertExists()
                val repeat=runBlocking { CompareExtractionModes(repo).compare(selected.baseline.runId,selected.assisted.runId,selected.truthSha256) }
                assertEquals(selected,repeat)
                runBlocking {
                    assertEquals(beforeRows,repo.observations(selectedRun.runId))
                    assertEquals(beforeAssessments,repo.assessments(selectedRun.runId).associateBy { it.observationId })
                    assertEquals(beforeComparisons,repo.comparisons(selectedRun.runId))
                }
                val state=JSONObject().put("documents",org.json.JSONArray(documents.map { it.id })).put("document",selectedRun.documentId)
                    .put("baseline",selected.baseline.runId).put("assisted",selected.assisted.runId).put("child",child.runId)
                    .put("truthHash",selected.truthSha256).put("pairHash",sha(ValidationJson.format.encodeToString(selected).toByteArray()))
                marker.writeText(state.toString()) // Opaque IDs/hashes only. Clinical content remains in protected repositories.
                event("measured",JSONObject().put("passed",true).put("pid",Process.myPid()).put("pairs",pairs.size)
                    .put("acceptedModelPages",pairs.sumOf { it.assisted.acceptedModelPages }))
                return
            }
            assertTrue(marker.exists());val ids=JSONObject(marker.readText())
            assertNull(importer.state.value.documentId)
            context.contentResolver.call(WorkbenchSyntheticProvider.URI,"revoke",null,null)
            openRun(ids.getString("document"),ids.getString("child"))
            assertEquals(OutputBasis.CORRECTED,results.state.value.run!!.outputBasis)
            assertEquals(ids.getString("assisted"),results.state.value.run!!.parentRunId)
            assertEquals(InferenceOutcome.NOT_ATTEMPTED,results.state.value.run!!.inferenceOutcome)
            assertTrue(results.state.value.rows.any { it.observation.resultRaw=="25" })
            compose.runOnIdle { results.showPage(1) }
            compose.waitUntil(15000) { results.state.value.preview!=null }
            compose.onNodeWithTag("results-list").performScrollToNode(hasTestTag("private-preview"))
            compose.onNodeWithTag("private-preview").assertIsDisplayed()
            val repeat=runBlocking { CompareExtractionModes(repo).compare(ids.getString("baseline"),ids.getString("assisted"),ids.getString("truthHash")) }
            assertEquals(ids.getString("pairHash"),sha(ValidationJson.format.encodeToString(repeat).toByteArray()))
            val documents=ids.getJSONArray("documents")
            for(i in 0 until documents.length()) {
                compose.runOnIdle { history.deleteDocument(documents.getString(i)) }
                compose.waitUntil(15000) { !history.state.value.actionBusy }
            }
            compose.waitUntil(15000) { results.state.value.unavailable }
            val artifacts=File(context.noBackupFilesDir,"artifacts")
            compose.waitUntil(60000) { (0 until documents.length()).all { !File(artifacts,documents.getString(it)).exists() } }
            val model=File(context.noBackupFilesDir,"models/gemma4.litertlm")
            assertTrue(model.isFile)
            assertEquals(br.com.exames.inferencelocal.LocalTextInference.MODEL_SHA256,model.inputStream().use { stream ->
                val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(1024*1024)
                while(true) { val n=stream.read(buffer);if(n<0) break;if(n>0) digest.update(buffer,0,n) };hex(digest.digest())
            })
            assertTrue(File(context.noBackupFilesDir,"ocr/tessdata/por.traineddata").exists())
            assertTrue(marker.delete())
            event("resumed-deleted",JSONObject().put("passed",true).put("pid",Process.myPid()).put("originalMetricsPreserved",true).put("staticWeightsPreserved",true))
        } finally { runBlocking { factory.open().close() } }
    }
    private fun event(phase: String,data: JSONObject) {
        data.put("phase",phase)
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply { putString("finalAcceptance",data.toString()) })
    }
    private fun sha(bytes: ByteArray)=hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray)=bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
