package me.rerere.rikkahub.data.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM wrapper whose key lives in Android Keystore under [keyAlias].
 * The provider generates the nonce on encrypt (Keystore keys are created with
 * randomized encryption required, so caller-supplied IVs are rejected); the
 * nonce bytes reported by [Cipher.getIV] are prepended to the ciphertext.
 * Decryption never creates a key and fails closed via [SshStorageCorruptException].
 */
internal class SshKeystoreCipher(
    private val keyAlias: String = DEFAULT_ALIAS,
    private val keyStoreProvider: String = ANDROID_KEYSTORE,
    private val specFactory: (String) -> KeyGenParameterSpec = ::defaultSpec,
) {
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val key = existingKey() ?: throw SshStorageCorruptException("SSH store key is missing")
        if (blob.size <= GCM_IV_BYTES) {
            throw SshStorageCorruptException("encrypted blob too short")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(0, GCM_IV_BYTES)),
            )
            cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
        } catch (e: Exception) {
            throw SshStorageCorruptException("failed to decrypt SSH store")
        }
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(keyStoreProvider).apply { load(null) }
        return store.getKey(keyAlias, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreProvider)
        generator.init(specFactory(keyAlias))
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        const val DEFAULT_ALIAS = "rikkahub_ssh_store"

        private fun defaultSpec(alias: String): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
    }
}

class SshStorageCorruptException(message: String, cause: Throwable? = null) : Exception(message, cause)
