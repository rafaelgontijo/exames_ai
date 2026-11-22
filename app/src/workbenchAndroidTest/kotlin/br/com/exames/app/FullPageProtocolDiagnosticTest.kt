package br.com.exames.app

import android.os.Bundle
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.app.di.InferenceDependencies
import br.com.exames.datalocal.*
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.inference.*
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.validation.*
import br.com.exames.featureimport.ImportViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Shape and equality diagnostics on the already-created synthetic fixture; no literal fields exported. */
class FullPageProtocolDiagnosticTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun explainStrictRejectionWithoutExportingSourceOrFinalText() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pageDiagnostic")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("br.com.exames.offline.validation.final",context.packageName)
        assertEquals("SM-S931B",android.os.Build.MODEL)
        compose.runOnIdle { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val factory=ProtectedDatabaseFactory(context);val repo=ProtectedValidationRepository(factory)
        val artifacts=EncryptedArtifactStore(context)
        val pages=PrivatePageLoader(repo,artifacts,PdfPreviewRenderer(context,LocalDataModule.pdfSource(artifacts,repo)))
        val adapter=EntryPointAccessors.fromApplication(context,InferenceDependencies::class.java).inference()
        try {
            var documents=repo.observeDocuments().first().filter { it.corpusCase!=null }
            if(documents.isEmpty()) {
                assertEquals("true",InstrumentationRegistry.getArguments().getString("bootstrapDevelopment"))
                context.contentResolver.call(WorkbenchSyntheticProvider.URI,"allow",context.packageName,null)
                lateinit var importer: ImportViewModel
                compose.runOnIdle {
                    importer=ViewModelProvider(compose.activity)[ImportViewModel::class.java]
                    importer.selectPackage(WorkbenchSyntheticProvider.CORPUS_URI)
                }
                compose.waitUntil(90000) { !importer.state.value.importing }
                assertEquals(8,importer.state.value.importedCases)
                context.contentResolver.call(WorkbenchSyntheticProvider.URI,"revoke",null,null)
                documents=repo.observeDocuments().first().filter { it.corpusCase!=null }
            }
            assertEquals(8,documents.size)
            assertTrue(documents.all { it.corpusCase!!.partition==CorpusPartition.DEVELOPMENT })
            for(document in documents.filter { it.corpusCase!!.format==CorpusFormat.PDF_TEXT }) {
                val format=document.corpusCase!!.format
                pages.load(document.id,1).use { page ->
                    val prompt=ProposalProtocol.compile(1,page.text.fragments)
                    val baseline=BiochemistryParser().parse(document.id,"diagnostic",1,page.text.fragments)
                    val result=adapter.generate(ExtractionMode.ASSISTED,prompt.request)
                    val data=JSONObject().put("format",format.name).put("inputUtf8Bytes",prompt.request.documentText.toByteArray().size)
                        .put("layoutId",document.corpusCase!!.layoutId).put("partition",document.corpusCase!!.partition.name)
                        .put("fragments",page.text.fragments.size).put("baselineRows",baseline.observations.size)
                    if(result is GenerationResult.UnvalidatedFinal) {
                        data.put("characters",result.text.length).put("loadMillis",result.loadMillis).put("generationMillis",result.generationMillis)
                        val decoded=ProposalProtocol.decode(result.text)
                        if(decoded is ProposalDecode.Valid) {
                            val validation=ProposalEvidenceValidator().validate(prompt,decoded.proposal,baseline)
                            data.put("accepted",validation.acceptedProposal!=null).put("issues",JSONArray(validation.issues.map { it.name }))
                            val rows=JSONArray()
                            fun spaces(s: String)=s.trim().replace(Regex("\\s+")," ")
                            for(candidate in decoded.proposal.observations) {
                                val analyte=BiochemistryParser.resolve(candidate.analyte.raw)
                                val original=baseline.observations.singleOrNull { it.observation.analyteNormalized==analyte?.name }?.observation
                                val row=JSONObject().put("analyte",analyte?.name).put("baselineFound",original!=null)
                                if(original!=null) {
                                    row.put("analyteEqual",spaces(candidate.analyte.raw)==spaces(original.analyteRaw))
                                    row.put("resultEqual",spaces(candidate.result?.raw.orEmpty())==spaces(original.resultRaw))
                                    row.put("unitEqual",spaces(candidate.unit?.raw.orEmpty())==spaces(original.unitRaw.orEmpty()))
                                    row.put("referencesEqual",candidate.references.map { spaces(it.raw) }.sorted()==original.referenceCandidates.map { spaces(it.referenceRaw) }.sorted())
                                    row.put("proposedReferenceCount",candidate.references.size).put("sourceReferenceCount",original.referenceCandidates.size)
                                    row.put("proposedReferenceLengths",JSONArray(candidate.references.map { it.raw.length }))
                                    row.put("sourceReferenceLengths",JSONArray(original.referenceCandidates.map { it.referenceRaw.length }))
                                    val contextEvidence=original.fieldEvidence.filter { it.field=="context" }
                                    row.put("contextPresent",candidate.context!=null).put("baselineContextPresent",contextEvidence.isNotEmpty())
                                    row.put("contextSpansEqual",candidate.context?.evidenceIds.orEmpty().mapNotNull { prompt.evidence[it]?.spanId }.toSet()==contextEvidence.map { it.spanId }.toSet())
                                }
                                rows.put(row)
                            }
                            data.put("rows",rows)
                        } else {
                            data.put("schemaValid",false).put("punctuationOnly",result.text.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""),"\"S\"")
                                .replace(Regex("[\\p{L}\\p{N}]"),"x").take(16000))
                        }
                    } else data.put("failure",(result as? GenerationResult.Failed)?.reason?.name)
                    InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply { putString("pageDiagnostic",data.toString()) })
                }
            }
        } finally { factory.open().close() }
    }
}
