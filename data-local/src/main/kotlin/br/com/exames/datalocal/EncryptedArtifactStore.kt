package br.com.exames.datalocal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Blocking IO: call from Dispatchers.IO. No plaintext clinical files are persisted. */
class EncryptedArtifactStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "artifacts")
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun directory(documentId: String): File {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(documentId))
        return File(root, documentId)
    }
    fun write(documentId: String, artifactId: String, input: InputStream): File {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(artifactId))
        val dir = directory(documentId).apply { check(isDirectory || mkdirs()) }
        val target = File(dir, "$artifactId.enc")
        check(!target.exists()) { "Artefato já existe" }
        val pending = File.createTempFile("pending-", ".enc", dir)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            pending.outputStream().use { output ->
                output.write(VERSION); output.write(cipher.iv.size); output.write(cipher.iv)
                CipherOutputStream(output, cipher).use { encrypted -> copyBounded(input, encrypted) }
            }
            check(pending.renameTo(target))
            return target
        } finally { pending.delete() }
    }
    /** Consume plaintext only in memory; do not render to unencrypted persistent temp files. */
    fun read(documentId: String, artifactId: String): ByteArray {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(artifactId))
        val file = File(directory(documentId), "$artifactId.enc")
        require(file.length() <= MAX_BYTES + 30)
        return file.inputStream().use { input ->
            require(input.read() == VERSION)
            val size = input.read(); require(size == 12)
            val iv = ByteArray(size)
            java.io.DataInputStream(input).readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            // doFinal authenticates the complete ciphertext before returning plaintext.
            val encrypted = input.readBytes()
            require(encrypted.size <= MAX_BYTES + 16)
            cipher.doFinal(encrypted)
        }
    }
    fun deleteDocument(documentId: String) {
        val dir = directory(documentId)
        check(!dir.exists() || dir.deleteRecursively())
    }
    private fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192); var total = 0L
        while (true) {
            val read = input.read(buffer); if (read < 0) break
            total += read; require(total <= MAX_BYTES) { "Arquivo excede o limite" }
            output.write(buffer, 0, read)
        }
    }
    companion object { private const val KEY_ALIAS = "exames-artifacts-v1"; private const val VERSION = 1
        private const val MAX_BYTES = 20L * 1024 * 1024 }
}
