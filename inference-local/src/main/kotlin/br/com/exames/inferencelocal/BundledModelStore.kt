package br.com.exames.inferencelocal

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

enum class ModelPreparationFailure { MISSING_ASSET, CORRUPT_ASSET, INSUFFICIENT_SPACE, STORAGE_UNAVAILABLE }
class ModelPreparationException(val reason: ModelPreparationFailure) : java.io.IOException(reason.name)

/** Static weights only. No prompts, generated text or sessions are written here. */
class BundledModelStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "models")
    private val preparer = AtomicModelPreparer(directory, ModelSpec(LocalTextInference.MODEL_BYTES,
        LocalTextInference.MODEL_SHA256), { context.assets.open("models/gemma4.litertlm") })
    suspend fun prepare(): File = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        preparer.prepare { caller.ensureActive() }
    }
}

internal data class ModelSpec(val size: Long, val sha256: String)
internal class AtomicModelPreparer(private val directory: File, private val spec: ModelSpec,
    private val asset: () -> InputStream, private val space: () -> Long = { directory.usableSpace }) {
    fun prepare(checkCancelled: () -> Unit = {}): File = synchronized(processLock) {
        try { prepareLocked(checkCancelled) }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (known: ModelPreparationException) { throw known }
        catch (_: Exception) { fail(ModelPreparationFailure.STORAGE_UNAVAILABLE) }
    }
    private fun prepareLocked(checkCancelled: () -> Unit): File {
        checkCancelled()
        if (!directory.isDirectory && !directory.mkdirs()) fail(ModelPreparationFailure.STORAGE_UNAVAILABLE)
        // File lock also protects preparation across a future separate inference process.
        RandomAccessFile(File(directory, ".prepare.lock"), "rw").use { lock ->
            lock.channel.use { channel -> channel.lock().use {
                checkCancelled()
                val target = File(directory, "gemma4.litertlm")
                directory.listFiles()?.filter { it.name.startsWith(".gemma4-") && it.name.endsWith(".partial") }
                    ?.forEach { if (!it.delete()) fail(ModelPreparationFailure.STORAGE_UNAVAILABLE) }
                if (valid(target, checkCancelled)) return target
                if (space() < spec.size + RESERVE_BYTES) fail(ModelPreparationFailure.INSUFFICIENT_SPACE)
                val temporary = File(directory, ".gemma4-${UUID.randomUUID()}.partial")
                try {
                    val input = try { asset() } catch (_: java.io.IOException) { fail(ModelPreparationFailure.MISSING_ASSET) }
                    input.use { source -> FileOutputStream(temporary).use { output ->
                        val hash = MessageDigest.getInstance("SHA-256")
                        val block = ByteArray(1024 * 1024); var count = 0L
                        while (true) {
                            checkCancelled()
                            val n = source.read(block); if (n < 0) break
                            count += n
                            if (count > spec.size) fail(ModelPreparationFailure.CORRUPT_ASSET)
                            output.write(block, 0, n); hash.update(block, 0, n)
                        }
                        if (count != spec.size || hex(hash.digest()) != spec.sha256) fail(ModelPreparationFailure.CORRUPT_ASSET)
                        output.fd.sync()
                    } }
                    checkCancelled()
                    Os.rename(temporary.absolutePath, target.absolutePath)
                    return target
                } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (known: ModelPreparationException) { throw known }
                catch (_: Exception) { fail(ModelPreparationFailure.STORAGE_UNAVAILABLE) }
                finally { temporary.delete() }
            } }
        }
    }
    private fun valid(file: File, checkCancelled: () -> Unit): Boolean {
        if (!file.isFile || file.length() != spec.size) return false
        val hash = MessageDigest.getInstance("SHA-256")
        return try {
            file.inputStream().use { input ->
                val block = ByteArray(1024 * 1024)
                while (true) { checkCancelled(); val n = input.read(block); if (n < 0) break; hash.update(block, 0, n) }
            }
            hex(hash.digest()) == spec.sha256
        } catch (_: java.io.IOException) { false }
    }
    private fun fail(reason: ModelPreparationFailure): Nothing = throw ModelPreparationException(reason)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    companion object {
        const val RESERVE_BYTES = 64L * 1024 * 1024
        private val processLock = Any()
    }
}
