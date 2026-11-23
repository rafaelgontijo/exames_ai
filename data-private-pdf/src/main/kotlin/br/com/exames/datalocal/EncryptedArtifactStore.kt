package br.com.exames.datalocal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Blocking IO: call from Dispatchers.IO. No plaintext clinical files are persisted. */
class ArtifactKeyUnavailableException : IOException("ARTIFACT_KEY_UNAVAILABLE")

class EncryptedArtifactStore @JvmOverloads constructor(context: Context, private val keyAlias: String = KEY_ALIAS) {
    private val root = File(context.noBackupFilesDir, "artifacts")
    private val guards = ArtifactDocumentLocks(context)
    private fun key(allowCreate: Boolean): SecretKey = guards.keyAccess {
        try { loadKey(allowCreate) }
        catch (_: Exception) { throw ArtifactKeyUnavailableException() }
    }
    private fun loadKey(allowCreate: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(keyAlias)) return store.getKey(keyAlias, null) as? SecretKey ?: throw ArtifactKeyUnavailableException()
        if (!allowCreate || root.walkTopDown().any { it.isFile && it.name.endsWith(".enc") }) throw ArtifactKeyUnavailableException()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun directory(documentId: String): File {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(documentId))
        return File(root, documentId)
    }
    fun write(documentId: String, artifactId: String, input: InputStream): File = guards.access(documentId) {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(artifactId))
        val dir = directory(documentId).apply { check(isDirectory || mkdirs()) }
        val target = File(dir, "$artifactId.enc")
        check(!target.exists()) { "Artefato já existe" }
        writeEncryptedFile(dir, target, input, artifactId)
    }

    /** Atomic authenticated overwrite restricted strictly to the encrypted history index. */
    fun overwriteHistoryIndex(input: InputStream): File = guards.access(HISTORY_INDEX_DOC) {
        val dir = directory(HISTORY_INDEX_DOC).apply { check(isDirectory || mkdirs()) }
        val target = File(dir, "$HISTORY_INDEX_ARTIFACT.enc")
        writeEncryptedFile(dir, target, input, HISTORY_INDEX_ARTIFACT).also {
            ArtifactDocumentLocks.sync(dir)
        }
    }

    fun exists(documentId: String, artifactId: String): Boolean = guards.access(documentId) {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(artifactId))
        File(directory(documentId), "$artifactId.enc").isFile
    }

    private fun writeEncryptedFile(dir: File, target: File, input: InputStream, artifactId: String): File {
        val encryptionKey = key(allowCreate = true)
        val pending = if (exportArtifact.matches(target.name)) {
            File(dir, "$artifactId.pending.enc").apply { check(createNewFile()) }
        } else File.createTempFile("pending-", ".enc", dir)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey) }
            pending.outputStream().use { output ->
                output.write(VERSION); output.write(cipher.iv.size); output.write(cipher.iv)
                CipherOutputStream(output, cipher).use { encrypted -> copyBounded(input, encrypted) }
            }
            check(pending.renameTo(target))
            return target
        } finally { pending.delete() }
    }
    /** Consume plaintext only in memory; do not render to unencrypted persistent temp files. */
    fun read(documentId: String, artifactId: String): ByteArray = guards.access(documentId) {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(artifactId))
        val file = File(directory(documentId), "$artifactId.enc")
        require(file.length() <= MAX_BYTES + 30)
        file.inputStream().use { input ->
            require(input.read() == VERSION)
            val size = input.read(); require(size == 12)
            val iv = ByteArray(size)
            java.io.DataInputStream(input).readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(allowCreate = false), GCMParameterSpec(128, iv))
            }
            // doFinal authenticates the complete ciphertext before returning plaintext.
            val encrypted = input.readBytes()
            require(encrypted.size <= MAX_BYTES + 16)
            cipher.doFinal(encrypted)
        }
    }
    /** Enumerates only export artifacts. Journal, source and model files are outside this namespace. */
    fun exportArtifacts(): List<Pair<String, String>> {
        if (!root.exists()) return emptyList()
        val documents = root.listFiles() ?: throw IOException("ARTIFACT_DIRECTORY_UNAVAILABLE")
        return documents.filter { it.isDirectory }.flatMap { dir ->
            val files = dir.listFiles() ?: if (dir.exists()) throw IOException("ARTIFACT_DIRECTORY_UNAVAILABLE") else emptyArray()
            files.mapNotNull { file -> exportArtifact.matchEntire(file.name)?.let { dir.name to it.groupValues[1] } }
        }
    }

    fun removeExport(documentId: String, artifactId: String) = guards.access(documentId) {
        require(exportArtifact.matches("$artifactId.enc"))
        val target = File(directory(documentId), "$artifactId.enc")
        check(!target.exists() || target.delete())
    }

    private val exportArtifact = Regex("""(share-(?:text|index)-[a-f0-9]{32}(?:\.pending)?)\.enc""")

    fun deleteDocument(documentId: String) = guards.access(documentId, deleting = true) {
        val dir = directory(documentId)
        check(!dir.exists() || dir.deleteRecursively())
        if (root.exists()) ArtifactDocumentLocks.sync(root)
    }
    private fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192); var total = 0L
        while (true) {
            val read = input.read(buffer); if (read < 0) break
            total += read; require(total <= MAX_BYTES) { "Arquivo excede o limite" }
            output.write(buffer, 0, read)
        }
    }
    companion object {
        private const val KEY_ALIAS = "exames-artifacts-v1"
        private const val VERSION = 1
        const val HISTORY_INDEX_DOC = "_history_index"
        const val HISTORY_INDEX_ARTIFACT = "index-v1"
        private val MAX_BYTES = br.com.exames.datadocuments.DocumentLimits.MAX_BYTES.toLong()
    }
}
