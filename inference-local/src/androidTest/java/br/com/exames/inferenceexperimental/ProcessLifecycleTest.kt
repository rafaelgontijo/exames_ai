package br.com.exames.inferencelocal

import android.content.ComponentName
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.domain.inference.*
import br.com.exames.domain.validation.ExtractionMode
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch

/** Only present in the test APK. No model/SDK call and no production test switches. */
class FakeInferenceService : OneShotInferenceService() {
    override fun generate(request: GenerationRequest, generating: () -> Unit): GenerationResult {
        when (request.documentText) {
            "load-hang" -> CountDownLatch(1).await()
            "crash" -> { Process.killProcess(Process.myPid()); CountDownLatch(1).await() }
            "throw" -> error("SYNTHETIC_EXCEPTION_CONTENT")
            "model-missing" -> return GenerationResult.Failed(InferenceFailure.MODEL_UNAVAILABLE)
            "model-corrupt" -> return GenerationResult.Failed(InferenceFailure.MODEL_CORRUPT)
        }
        generating()
        if (request.documentText == "generation-hang") CountDownLatch(1).await()
        return GenerationResult.UnvalidatedFinal(if (request.documentText == "large") "x".repeat(16385) else request.documentText, 1, 2)
    }
}

class ProcessLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun client(load: Long = 5000, generation: Long = 5000) = ProcessInferenceClient(context,
        ComponentName(context, FakeInferenceService::class.java), load, generation)
    private fun request(text: String) = GenerationRequest("same-prefix-synthetic", text, "test/1", "test/1")
    private suspend fun waiting(client: ProcessInferenceClient) = withTimeout(5000) {
        while (!client.generationStarted) delay(10)
    }

    @Test fun successUsesDifferentChildProcessesForSharedPrefixesAndKeepsParentAlive() = runBlocking {
        val parent = Process.myPid(); val transport = client()
        val a = transport.generate(request("first-synthetic")) as GenerationResult.UnvalidatedFinal
        val first = transport.lastProcessId!!
        val b = transport.generate(request("second-synthetic")) as GenerationResult.UnvalidatedFinal
        val second = transport.lastProcessId!!
        assertEquals("first-synthetic", a.text); assertEquals("second-synthetic", b.text)
        assertNotEquals(parent, first); assertNotEquals(parent, second); assertNotEquals(first, second)
        assertEquals(parent, Process.myPid())
    }
    @Test fun deadlinesKillBlockedChildThreadsDuringLoadAndGeneration() = runBlocking {
        val load = client(load = 1500)
        val start = SystemClock.elapsedRealtime()
        assertEquals(GenerationResult.Failed(InferenceFailure.LOAD_TIMEOUT), load.generate(request("load-hang")))
        assertNotNull(load.lastProcessId)
        val generation = client(generation = 250)
        assertEquals(GenerationResult.Failed(InferenceFailure.GENERATION_TIMEOUT), generation.generate(request("generation-hang")))
        assertTrue(SystemClock.elapsedRealtime() - start < 10000)
        assertTrue(client().generate(request("after-timeouts")) is GenerationResult.UnvalidatedFinal)
    }
    @Test fun callerCancellationKillsChildDropsLateResponseAndAllowsFreshRequest() = runBlocking {
        val transport = client(); var published = false
        val job = launch { transport.generate(request("generation-hang")); published = true }
        waiting(transport); val old = transport.lastProcessId!!
        withTimeout(6000) { job.cancelAndJoin() }
        assertFalse(published)
        assertTrue(transport.generate(request("after-cancel")) is GenerationResult.UnvalidatedFinal)
        assertNotEquals(old, transport.lastProcessId)
    }
    @Test fun onlyOneRequestAcrossAdapterInstancesIsAllowedAndBaselineNeverBinds() = runBlocking {
        val transport = client(); val first = async { transport.generate(request("generation-hang")) }
        waiting(transport)
        val second = client()
        assertEquals(GenerationResult.Failed(InferenceFailure.BUSY), second.generate(request("other")))
        assertNull(second.lastProcessId)
        val adapter = LocalTextInference(context, second)
        assertSame(GenerationResult.NotRequested, adapter.generate(ExtractionMode.BASELINE, request("other")))
        first.cancelAndJoin()
    }
    @Test fun crashAndExceptionsBecomeSanitizedFailuresAndOversizedInputDoesNotBind() = runBlocking {
        val transport = client()
        assertEquals(GenerationResult.Failed(InferenceFailure.PROCESS_DIED), transport.generate(request("crash")))
        assertEquals(GenerationResult.Failed(InferenceFailure.RUNTIME_FAILURE), transport.generate(request("throw")))
        assertEquals(GenerationResult.Failed(InferenceFailure.OUTPUT_LIMIT), transport.generate(request("large")))
        assertEquals(GenerationResult.Failed(InferenceFailure.MODEL_UNAVAILABLE), transport.generate(request("model-missing")))
        assertEquals(GenerationResult.Failed(InferenceFailure.MODEL_CORRUPT), transport.generate(request("model-corrupt")))
        val oversized = client()
        assertEquals(GenerationResult.Failed(InferenceFailure.INPUT_LIMIT), oversized.generate(request("x".repeat(16384))))
        assertNull(oversized.lastProcessId)
    }
    @Test fun productionAndTestServicesArePrivateAndSeparateFromParent() {
        for (type in listOf(InferenceProcessService::class.java, FakeInferenceService::class.java)) {
            val service = context.packageManager.getServiceInfo(ComponentName(context, type), 0)
            assertFalse(service.exported); assertNotEquals(context.applicationInfo.processName, service.processName)
        }
    }
    @Test fun productionServiceReportsMissingAssetWithoutStartingNativeEngine() = runBlocking {
        assumeTrue(!File(context.noBackupFilesDir, "models/gemma4.litertlm").exists())
        assumeTrue(!context.assets.list("models").orEmpty().contains("gemma4.litertlm"))
        assumeTrue(context.noBackupFilesDir.usableSpace > LocalTextInference.MODEL_BYTES + 64L * 1024 * 1024)
        assertEquals(GenerationResult.Failed(InferenceFailure.MODEL_UNAVAILABLE),
            LocalTextInference(context).generate(ExtractionMode.ASSISTED, request("synthetic-missing-model")))
    }
}
