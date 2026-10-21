package br.com.exames.datalocal

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PayloadIntegrityException : GeneralSecurityException("Payload authentication failed")
class PayloadKeyUnavailableException : GeneralSecurityException("Local encryption key is unavailable")

/** Authenticated envelope for Room BLOBs. Key creation is an explicit installation operation. */
class EncryptedPayloadCodec(private val existingKey: () -> SecretKey) {
    fun encrypt(entityType: String, entityId: String, schemaVersion: String, plaintext: ByteArray): ByteArray {
        require(plaintext.size <= MAX_PAYLOAD_BYTES) { "Payload exceeds limit" }
        val aad = associatedData(entityType, entityId, schemaVersion)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, existingKey())
        check(cipher.iv.size == IV_BYTES)
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + IV_BYTES + encrypted.size)
            .put(ENVELOPE_VERSION).put(cipher.iv).put(encrypted).array()
    }

    fun decrypt(entityType: String, entityId: String, schemaVersion: String, envelope: ByteArray): ByteArray {
        val aad = associatedData(entityType, entityId, schemaVersion)
        if (envelope.size !in (1 + IV_BYTES + TAG_BYTES)..(1 + IV_BYTES + TAG_BYTES + MAX_PAYLOAD_BYTES)
            || envelope[0] != ENVELOPE_VERSION) throw PayloadIntegrityException()
        val iv = envelope.copyOfRange(1, 1 + IV_BYTES)
        val key = existingKey() // A lost key must never be replaced while opening existing content.
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(envelope, 1 + IV_BYTES, envelope.size - 1 - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            throw PayloadIntegrityException()
        }
    }

    private fun associatedData(type: String, id: String, schema: String): ByteArray {
        require(type.matches(Regex("[a-z][a-z0-9_-]{0,63}")))
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        require(schema.matches(Regex("[a-zA-Z0-9_.-]{1,32}")))
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeByte(ENVELOPE_VERSION.toInt())
            for (value in listOf(type, id, schema)) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                stream.writeInt(bytes.size)
                stream.write(bytes)
            }
        }
        return output.toByteArray()
    }

    companion object {
        private const val ENVELOPE_VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
    }
}
