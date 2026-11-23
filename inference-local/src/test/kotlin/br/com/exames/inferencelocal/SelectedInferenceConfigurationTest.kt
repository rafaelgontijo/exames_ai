package br.com.exames.inferencelocal

import br.com.exames.domain.inference.ProposalProtocol
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SelectedInferenceConfigurationTest {
    @Test fun attestedProductionIdentityMatchesPinnedDistributionAndRuntimeSelection() {
        val root = File(requireNotNull(System.getProperty("exames.projectRoot")))
        val selection = Json.parseToJsonElement(File(root, "validation/models/selection.json").readText()).jsonObject
        val lock = Json.parseToJsonElement(File(root, "validation/models/gemma4-e2b/model.lock.json").readText()).jsonObject
        fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
        val actual = SelectedInferenceConfiguration.forProtocol()
        assertEquals(selection.s("modelId"), actual.modelId)
        assertEquals(lock.s("revision"), actual.modelRevision); assertEquals(selection.s("modelRevision"), actual.modelRevision)
        assertEquals(lock.s("sha256"), actual.modelSha256); assertEquals(selection.s("modelSha256"), actual.modelSha256)
        assertEquals(lock.s("sizeBytes").toLong(), SelectedInferenceConfiguration.MODEL_BYTES)
        assertEquals(lock.s("quantization"), actual.quantization)
        assertEquals(selection.getValue("runtime").jsonObject.s("coordinate"), actual.runtimeVersion)
        assertEquals(selection.getValue("runtime").jsonObject.s("aarSha256"), actual.generationParameters["runtimeAarSha256"])
        assertEquals(System.getProperty("exames.runtimeCoordinate"), actual.runtimeVersion)
        assertEquals(System.getProperty("exames.runtimeAarSha256"), actual.generationParameters["runtimeAarSha256"])
        assertEquals(selection.s("backend"), actual.backend)
        assertEquals(selection.s("visionBackend"), actual.generationParameters["visionBackend"])
        assertEquals(selection.s("maxNumImages"), actual.generationParameters["maxNumImages"])
        assertEquals(selection.s("contextTokens").toInt(), actual.maxContextTokens)
        assertEquals(selection.s("maxOutputTokens").toInt(), actual.maxGenerationTokens)
        assertEquals(ProposalProtocol.PROMPT_VERSION, actual.promptVersion)
        assertEquals(ProposalProtocol.SCHEMA_VERSION, actual.schemaVersion)
        assertNull(actual.thinkingBudget)
        val sampler = selection.getValue("sampler").jsonObject
        for (key in listOf("topK", "topP", "temperature", "seed")) assertEquals(sampler.s(key), actual.generationParameters[key])
        for (key in listOf("api", "activationDataType", "nativeResponseFormat", "freshEnginePerRequest", "nativeCache"))
            assertEquals(selection.s(key), actual.generationParameters[key])
        val lifecycle = selection.getValue("lifecycle").jsonObject
        for (key in listOf("freshProcessPerRequest", "loadTimeoutMillis", "generationTimeoutMillis", "retireTimeoutMillis",
            "maxInputUtf8Bytes", "maxImageBytes", "maxFinalCharacters", "concurrency", "overflowPolicy"))
            assertEquals(lifecycle.s(key), actual.generationParameters[key])
        assertEquals(lifecycle.s("generationTimeoutMillis").toLong(), actual.timeoutMillis)
    }
}
