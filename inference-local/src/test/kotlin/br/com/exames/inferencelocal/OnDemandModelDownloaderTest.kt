package br.com.exames.inferencelocal

import br.com.exames.domain.inference.LocalWeightsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class OnDemandModelDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun downloadsVerifiesSha256AtomicallyPromotesAndSupportsDeletion() = runBlocking {
        val payload = ByteArray(16 * 1024) { (it * 31).toByte() }
        val sha = sha256(payload)
        val dir = temp.newFolder("models")
        val spec = ModelSpec(payload.size.toLong(), sha)

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        server.start()
        try {
            val downloader = OkHttpModelDownloader(
                directory = dir,
                spec = spec,
                allowCleartextForLocalTests = true,
                space = { payload.size.toLong() + AtomicModelPreparer.RESERVE_BYTES + 1024L },
            )
            var lastBytes = 0L
            val promoted = downloader.download(server.url("/gemma4.litertlm").toString()) { done, total ->
                lastBytes = done
                assertEquals(payload.size.toLong(), total)
            }
            assertTrue(promoted.isFile)
            assertEquals(payload.size.toLong(), promoted.length())
            assertEquals(payload.size.toLong(), lastBytes)
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".partial") })

            val core = VerifiedModelStreamDownloader(dir, spec)
            assertEquals(LocalWeightsState.READY, core.inspectState())
            assertTrue(core.deleteLocalWeights())
            assertFalse(promoted.exists())
            assertEquals(LocalWeightsState.NOT_DOWNLOADED, core.inspectState())
        } finally {
            server.shutdown()
        }
    }

    @Test fun rejectsCorruptHashAndDeletesTemporaryPartialFile() {
        val payload = ByteArray(4096) { 0x42 }
        val dir = temp.newFolder("corrupt")
        val spec = ModelSpec(payload.size.toLong(), "0".repeat(64))
        val core = VerifiedModelStreamDownloader(
            directory = dir,
            spec = spec,
            space = { payload.size.toLong() + AtomicModelPreparer.RESERVE_BYTES + 1024L },
        )
        try {
            core.download(openStream = { ByteArrayInputStream(payload) })
            fail("Expected CORRUPT_ASSET")
        } catch (e: ModelPreparationException) {
            assertEquals(ModelPreparationFailure.CORRUPT_ASSET, e.reason)
        }
        assertFalse(File(dir, VerifiedModelStreamDownloader.MODEL_FILENAME).exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".partial") })
    }

    @Test fun failsBeforeStreamingWhenDiskSpaceIsInsufficient() {
        val dir = temp.newFolder("nospace")
        val spec = ModelSpec(10_000L, "0".repeat(64))
        var streamOpened = false
        val core = VerifiedModelStreamDownloader(
            directory = dir,
            spec = spec,
            space = { 1024L },
        )
        assertEquals(LocalWeightsState.INSUFFICIENT_SPACE, core.inspectState())
        try {
            core.download(openStream = {
                streamOpened = true
                ByteArrayInputStream(ByteArray(10_000))
            })
            fail("Expected INSUFFICIENT_SPACE")
        } catch (e: ModelPreparationException) {
            assertEquals(ModelPreparationFailure.INSUFFICIENT_SPACE, e.reason)
        }
        assertFalse(streamOpened)
    }

    @Test fun abortsAndCleansPartialWhenCancelledMidStream() {
        val payload = ByteArray(8192) { 0x11 }
        val dir = temp.newFolder("cancel")
        val spec = ModelSpec(payload.size.toLong(), sha256(payload))
        val core = VerifiedModelStreamDownloader(
            directory = dir,
            spec = spec,
            space = { payload.size.toLong() + AtomicModelPreparer.RESERVE_BYTES + 1024L },
        )
        var checks = 0
        try {
            core.download(
                openStream = {
                    object : InputStream() {
                        private var idx = 0
                        override fun read(): Int = if (idx < payload.size) payload[idx++].toInt() and 0xFF else -1
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (idx >= payload.size) return -1
                            val count = minOf(len, 1024, payload.size - idx)
                            System.arraycopy(payload, idx, b, off, count)
                            idx += count
                            return count
                        }
                    }
                },
                checkCancelled = {
                    checks++
                    if (checks >= 4) throw CancellationException("USER_CANCELLED")
                },
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected
        }
        assertFalse(File(dir, VerifiedModelStreamDownloader.MODEL_FILENAME).exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".partial") })
    }

    @Test fun inspectStateChecksExistingParentWhenModelsDirectoryDoesNotExistYet() {
        val parent = temp.newFolder("noBackupFilesDir")
        val nonExistentModelsDir = File(parent, "models")
        assertFalse(nonExistentModelsDir.exists())
        val spec = ModelSpec(1024L, sha256(ByteArray(1024)))
        val core = VerifiedModelStreamDownloader(
            directory = nonExistentModelsDir,
            spec = spec,
        )
        assertEquals(LocalWeightsState.NOT_DOWNLOADED, core.inspectState())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
