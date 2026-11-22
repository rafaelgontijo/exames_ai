package br.com.exames.datalocal

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One permanent byte per opaque document ID. No source, URI, clinical field or key material. */
internal class ArtifactDocumentLocks(context: Context) {
    private val parent = context.noBackupFilesDir
    private val directory = File(parent, "artifact-guards")
    fun <T> access(id: String, deleting: Boolean = false, block: () -> T): T {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        return accessFile(File(directory, id), deleting, block)
    }
    fun <T> keyAccess(block: () -> T): T = accessFile(File(directory, ".key"), false, block)
    private fun <T> accessFile(lockFile: File, deleting: Boolean, block: () -> T): T {
        return locks.getOrPut(lockFile.absolutePath) { ReentrantLock() }.withLock {
            check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
            sync(parent)
            RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use {
                file.seek(0)
                if (!deleting && file.length() != 0L) {
                    if (file.length() == 1L && file.read() == 1) throw IOException("ARTIFACT_DOCUMENT_DELETED")
                    throw IOException("ARTIFACT_GUARD_INVALID")
                }
                if (deleting) {
                    file.seek(0); file.write(1); file.setLength(1); file.fd.sync(); sync(directory)
                }
                block()
            } }
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        fun sync(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }
}
