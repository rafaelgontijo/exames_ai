package br.com.exames.datalocal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArtifactKeyRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun isolated(root: File) = object : ContextWrapper(context) {
        override fun getNoBackupFilesDir(): File = root.apply { mkdirs() }
    }
    @Test fun lostArtifactKeyIsNotRegeneratedOnReadOrWriteAndCiphertextRemainsUntouched() {
        val suffix = UUID.randomUUID().toString(); val root = File(context.noBackupFilesDir, "key-test-$suffix"); val alias = "artifact-test-$suffix"
        val isolated = isolated(root); val store = EncryptedArtifactStore(isolated, alias)
        val id = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        try {
            val encrypted = store.write(id, "original", "synthetic-private-source".byteInputStream())
            val before = encrypted.readBytes()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            try { EncryptedArtifactStore(isolated, alias).read(id, "original"); fail("Key regenerated on read") }
            catch (_: ArtifactKeyUnavailableException) { }
            try { EncryptedArtifactStore(isolated, alias).write(other, "original", "other".byteInputStream()); fail("Key regenerated with inaccessible ciphertext present") }
            catch (_: ArtifactKeyUnavailableException) { }
            assertArrayEquals(before, encrypted.readBytes())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            assertFalse(File(root, "artifacts/$other/original.enc").exists())
        } finally {
            root.deleteRecursively(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun firstConcurrentWritersAcrossStoreInstancesShareOneKeyAndBothDecrypt() = runBlocking {
        val suffix = UUID.randomUUID().toString(); val root = File(context.noBackupFilesDir, "key-test-$suffix"); val alias = "artifact-test-$suffix"
        val isolated = isolated(root); val start = CompletableDeferred<Unit>()
        val ids = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        try {
            val writers = ids.mapIndexed { index, id -> async(Dispatchers.IO) {
                start.await(); EncryptedArtifactStore(isolated, alias).write(id, "original", "synthetic-$index".byteInputStream())
            } }
            start.complete(Unit); writers.awaitAll()
            for ((index, id) in ids.withIndex()) assertEquals("synthetic-$index", EncryptedArtifactStore(isolated, alias).read(id, "original").toString(Charsets.UTF_8))
        } finally {
            root.deleteRecursively(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
