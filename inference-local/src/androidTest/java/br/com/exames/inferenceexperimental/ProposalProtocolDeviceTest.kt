package br.com.exames.inferenceexperimental

import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.domain.inference.*
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.domain.validation.SourceRegion
import br.com.exames.inferencelocal.LocalTextInference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProposalProtocolDeviceTest {
    @Test fun realModelProposesVersionedFieldsWithValidatedSourceIds() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("proposalProtocol") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, SpikeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertFalse("DEVICE_LOCKED", (context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked)
        val adapter = LocalTextInference(context)
        for ((index, value) in listOf("82", "117").withIndex()) {
            fun span(id: String, raw: String, x: Double, y: Double, width: Double = 70.0) =
                SourceFragment(id, raw, SourceRegion(1, x, y, x + width, y + 14, 600.0, 800.0), "SYNTHETIC", "protocol-control/1")
            val fragments = listOf(span("layout", "Layout: simple-v1", 25.0, 50.0, 500.0),
                span("header", "Analito Resultado Unidade Referência", 25.0, 130.0, 500.0),
                span("analyte", "Glicose", 25.0, 175.0), span("result", value, 175.0, 175.0),
                span("unit", "mg/dL", 270.0, 175.0), span("reference", "70 a 99 mg/dL", 360.0, 175.0, 220.0))
            val prompt = ProposalProtocol.compile(1, fragments)
            val baseline = BiochemistryParser().parse("synthetic", "protocol-$index", 1, fragments)
            status(index, "load", JSONObject())
            val output = adapter.generate(ExtractionMode.ASSISTED, prompt.request)
            if (output is GenerationResult.Failed) status(index, "failure", JSONObject().put("errorCode", output.reason.name))
            assertTrue("PROTOCOL_NO_FINAL", output is GenerationResult.UnvalidatedFinal)
            output as GenerationResult.UnvalidatedFinal
            val decoded = ProposalProtocol.decode(output.text)
            if (decoded !is ProposalDecode.Valid) {
                status(index, "result", JSONObject().put("validEvidenceJson", false).put("schemaValid", false)
                    .put("loadMs", output.loadMillis).put("generationMs", output.generationMillis)
                    .put("shape", shape(output.text)))
                fail("PROTOCOL_SCHEMA_REJECTED")
            }
            decoded as ProposalDecode.Valid
            val validation = ProposalEvidenceValidator().validate(prompt, decoded.proposal, baseline)
            val candidate = decoded.proposal.observations.singleOrNull()
            val matches = JSONObject().put("analito", candidate?.analyte?.raw == "Glicose")
                .put("valor", candidate?.result?.raw == value).put("unidade", candidate?.unit?.raw == "mg/dL")
                .put("referencia", candidate?.references?.singleOrNull()?.raw == "70 a 99 mg/dL")
                .put("evidencia", validation.issues.isEmpty())
            val valid = validation.acceptedProposal != null && listOf("analito", "valor", "unidade", "referencia", "evidencia").all { matches.getBoolean(it) }
            status(index, "result", JSONObject().put("validEvidenceJson", valid).put("schemaValid", true)
                .put("fieldMatches", matches).put("issueCodes", validation.issues.map { it.name }.joinToString(","))
                .put("loadMs", output.loadMillis).put("generationMs", output.generationMillis))
            assertTrue("PROTOCOL_EVIDENCE_REJECTED", valid)
        }
    }
    private fun status(index: Int, phase: String, metrics: JSONObject) {
        metrics.put("case", index).put("phase", phase)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("lfm_spike", metrics.toString()) })
    }
    private fun shape(text: String): JSONObject {
        val metrics = JSONObject().put("characters", text.length).put("startsObject", text.trimStart().startsWith("{"))
            .put("hasMarkdownFence", text.contains("```"))
        try {
            val root = org.json.JSONTokener(text).nextValue() as? JSONObject ?: return metrics
            metrics.put("rootKeys", root.length()).put("schemaMatches", root.optString("schema") == ProposalProtocol.SCHEMA_VERSION)
            val observations = root.optJSONArray("observations")
            metrics.put("hasObservationsArray", observations != null).put("observationCount", observations?.length() ?: -1)
            val first = observations?.optJSONObject(0)
            if (first != null) for (key in listOf("analyte", "result", "unit", "references", "context")) {
                val value = first.opt(key)
                metrics.put(key + "Type", if (value == JSONObject.NULL) "null" else value?.javaClass?.simpleName ?: "missing")
                if (value is JSONObject) {
                    metrics.put(key + "Keys", value.length()).put(key + "HasRaw", value.has("raw"))
                        .put(key + "HasSpans", value.has("spans"))
                        .put(key + "HasSpanArray", value.optJSONArray("spans") != null)
                }
            }
        } catch (_: Exception) { metrics.put("jsonSyntax", false) }
        return metrics
    }
}
