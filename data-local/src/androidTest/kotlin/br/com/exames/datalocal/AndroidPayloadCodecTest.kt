package br.com.exames.datalocal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPayloadCodecTest {
    private val alias = "synthetic-test-${UUID.randomUUID()}"
    private val keys = AndroidPayloadKeys(alias)
    private val codec = EncryptedPayloadCodec(keys::existing)

    @Before fun createKey() { keys.createForNewStore() }
    @After fun removeKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }

    @Test fun platformKeystoreAuthenticatesPayloadAndRowIdentity() {
        assertNull(keys.existing().encoded)
        val payload = "SINTÉTICO; nenhum dado real".toByteArray(Charsets.UTF_8)
        val first = codec.encrypt("observation", "test-id", "2", payload)
        val second = codec.encrypt("observation", "test-id", "2", payload)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(payload, codec.decrypt("observation", "test-id", "2", first))
        assertThrows(PayloadIntegrityException::class.java) { codec.decrypt("observation", "other-id", "2", first) }
        assertThrows(PayloadIntegrityException::class.java) { codec.decrypt("assessment", "test-id", "2", first) }
        assertThrows(PayloadIntegrityException::class.java) { codec.decrypt("observation", "test-id", "3", first) }
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(PayloadIntegrityException::class.java) { codec.decrypt("observation", "test-id", "2", first) }
    }

    @Test fun lostKeyDoesNotRegenerateOnRead() {
        val envelope = codec.encrypt("observation", "test-id", "2", byteArrayOf(1, 2, 3))
        removeKey()
        assertThrows(PayloadKeyUnavailableException::class.java) { codec.decrypt("observation", "test-id", "2", envelope) }
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }
}
