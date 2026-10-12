package com.cardscanner.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encryption boundary: production uses a non-exportable Android Keystore key. */
internal interface CredentialCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(encoded: String): String
}

internal class KeystoreCredentialCipher(private val alias: String) : CredentialCipher {
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(): SecretKey = synchronized(keyLock) {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey()) // Provider generates a fresh IV.
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        return "v1:${encode(cipher.iv)}:${encode(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))}"
    }

    override fun decrypt(encoded: String): String {
        val parts = encoded.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Unsupported credential format" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        require(iv.size == 12) { "Invalid credential IV" }
        // Never generate a new key when reading: that would hide loss of the original key.
        val key = keyStore().getKey(alias, null) as? SecretKey ?: error("Credential key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        private val keyLock = Any()
    }
}
