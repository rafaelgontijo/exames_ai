package br.com.exames.datalocal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** No automatic key recreation on reads, migration retries or failures. */
class AndroidPayloadKeys(private val alias: String = "exames-payloads-v1") {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(): Boolean = store().containsAlias(alias)

    @Synchronized
    fun existing(): SecretKey {
        try {
            return store().getKey(alias, null) as? SecretKey ?: throw PayloadKeyUnavailableException()
        } catch (_: java.security.GeneralSecurityException) {
            throw PayloadKeyUnavailableException()
        } catch (_: java.io.IOException) {
            throw PayloadKeyUnavailableException()
        }
    }

    /** Caller must establish that this is a new protected store, not one whose key was lost. */
    @Synchronized
    fun createForNewStore() {
        check(!store().containsAlias(alias)) { "Encryption key already exists" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}
