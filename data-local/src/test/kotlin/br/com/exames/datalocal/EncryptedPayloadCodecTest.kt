package br.com.exames.datalocal

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class EncryptedPayloadCodecTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val codec = EncryptedPayloadCodec { key }
    private val plaintext = "SINTÉTICO: unidade e referência".toByteArray()

    @Test fun roundTripAndUniqueNonces() {
        val first = codec.encrypt("observation", "id_1", "2", plaintext)
        val second = codec.encrypt("observation", "id_1", "2", plaintext)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains("SINTÉTICO"))
        assertArrayEquals(plaintext, codec.decrypt("observation", "id_1", "2", first))
        assertArrayEquals(plaintext, codec.decrypt("observation", "id_1", "2", second))
    }

    @Test fun rowTypeAndSchemaAreAuthenticated() {
        val envelope = codec.encrypt("observation", "id_1", "2", plaintext)
        assertThrows(PayloadIntegrityException::class.java) { codec.decrypt("observation", "id_2", "2", envelope) }
        assertThrows(PayloadIntegrityException::class.java) { codec.decrypt("assessment", "id_1", "2", envelope) }
        assertThrows(PayloadIntegrityException::class.java) { codec.decrypt("observation", "id_1", "3", envelope) }
    }

    @Test fun tamperingTruncationAndUnknownVersionsAreRejected() {
        val envelope = codec.encrypt("observation", "id_1", "2", plaintext)
        envelope.indices.forEach { index ->
            val tampered = envelope.copyOf()
            tampered[index] = (tampered[index].toInt() xor 1).toByte()
            assertThrows(PayloadIntegrityException::class.java) { codec.decrypt("observation", "id_1", "2", tampered) }
        }
        listOf(byteArrayOf(), envelope.copyOf(10), envelope.copyOf(envelope.size - 1)).forEach {
            assertThrows(PayloadIntegrityException::class.java) { codec.decrypt("observation", "id_1", "2", it) }
        }
    }

    @Test fun keyLossCannotCreateReplacementOrReturnPlaintext() {
        val envelope = codec.encrypt("observation", "id_1", "2", plaintext)
        val unavailable = EncryptedPayloadCodec { throw PayloadKeyUnavailableException() }
        assertThrows(PayloadKeyUnavailableException::class.java) {
            unavailable.decrypt("observation", "id_1", "2", envelope)
        }
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(PayloadIntegrityException::class.java) {
            EncryptedPayloadCodec { wrongKey }.decrypt("observation", "id_1", "2", envelope)
        }
    }

    @Test fun payloadLimitsAreCheckedBeforeEncryption() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encrypt("observation", "id_1", "2", ByteArray(EncryptedPayloadCodec.MAX_PAYLOAD_BYTES + 1))
        }
        val empty = codec.encrypt("observation", "id_1", "2", byteArrayOf())
        assertArrayEquals(byteArrayOf(), codec.decrypt("observation", "id_1", "2", empty))
    }
}
