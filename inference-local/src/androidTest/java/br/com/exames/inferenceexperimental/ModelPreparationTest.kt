package br.com.exames.inferenceexperimental

import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.inferencelocal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModelPreparationTest {
    private val bytes = ByteArray(4096) { (it % 23).toByte() }
    private val spec = ModelSpec(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun withDirectory(body: (File) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "preparation-${UUID.randomUUID()}")
        try { body(directory) } finally { directory.deleteRecursively() }
    }
    private fun failure(expected: ModelPreparationFailure, body: () -> Unit) {
        try { body(); fail("Accepted invalid model preparation") }
        catch (failure: ModelPreparationException) { assertEquals(expected, failure.reason); assertNull(failure.cause) }
    }
    @Test fun copiesVerifiesReopensAndRepairsWithoutTrustingSizeAlone() = withDirectory { directory ->
        val reads = AtomicInteger()
        val store = AtomicModelPreparer(directory, spec, { reads.incrementAndGet(); bytes.inputStream() })
        val target = store.prepare()
        assertArrayEquals(bytes, target.readBytes()); assertEquals(1, reads.get())
        File(directory, ".gemma4-interrupted.partial").writeBytes(byteArrayOf(1))
        assertEquals(target, store.prepare()); assertEquals(1, reads.get())
        assertFalse(File(directory, ".gemma4-interrupted.partial").exists())
        target.writeBytes(bytes.copyOf().apply { this[0] = 90 })
        assertArrayEquals(bytes, store.prepare().readBytes()); assertEquals(2, reads.get())
    }
    @Test fun rejectsMissingTruncatedOversizedAndWrongHashAssetsWithoutPublishing() = withDirectory { directory ->
        failure(ModelPreparationFailure.MISSING_ASSET) { AtomicModelPreparer(directory, spec, { throw IOException("SYNTHETIC SENSITIVE CAUSE") }).prepare() }
        for (source in listOf(bytes.copyOf(20), bytes + 1.toByte(), bytes.copyOf().apply { this[0] = 90 })) {
            failure(ModelPreparationFailure.CORRUPT_ASSET) { AtomicModelPreparer(directory, spec, { source.inputStream() }).prepare() }
            assertFalse(File(directory, "gemma4.litertlm").exists())
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".partial") })
        }
    }
    @Test fun exactSpaceBoundaryAndReadFailureAreExplicit() = withDirectory { directory ->
        val required = spec.size + AtomicModelPreparer.RESERVE_BYTES
        failure(ModelPreparationFailure.INSUFFICIENT_SPACE) {
            AtomicModelPreparer(directory, spec, { fail("Asset opened without space"); bytes.inputStream() }, { required - 1 }).prepare()
        }
        failure(ModelPreparationFailure.STORAGE_UNAVAILABLE) {
            AtomicModelPreparer(directory, spec, { object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("SYNTHETIC SENSITIVE CAUSE")
            } }, { required }).prepare()
        }
        assertArrayEquals(bytes, AtomicModelPreparer(directory, spec, { bytes.inputStream() }, { required }).prepare().readBytes())
    }
    @Test fun cancellationNeverPublishesAndConcurrentPreparersShareOneVerifiedCopy() = withDirectory { directory ->
        var checks = 0
        try { AtomicModelPreparer(directory, spec, { bytes.inputStream() }).prepare { if (++checks >= 4) throw CancellationException() }; fail() }
        catch (_: CancellationException) { }
        assertFalse(File(directory, "gemma4.litertlm").exists())
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".partial") })
        val reads = AtomicInteger(); val executor = Executors.newFixedThreadPool(2)
        try {
            val stores = (1..2).map { AtomicModelPreparer(directory, spec, { reads.incrementAndGet(); bytes.inputStream() }) }
            val futures = stores.map { executor.submit<File> { it.prepare() } }
            futures.forEach { assertArrayEquals(bytes, it.get(10, TimeUnit.SECONDS).readBytes()) }
            assertEquals(1, reads.get())
        } finally { executor.shutdownNow() }
    }
}
