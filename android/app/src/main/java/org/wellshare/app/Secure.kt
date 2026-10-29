package org.wellshare.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts values kept on the phone (upload token, device id, website log) with AES-256-GCM.
 * The key lives in the Android Keystore: it never leaves secure hardware where available
 * and can't be copied out of the phone, even by WellShare itself.
 */
object Secure {
    private const val ALIAS = "wellshare.storage.v1"
    const val PREFIX = "enc1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Volatile private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val k = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
        cachedKey = k
        return k
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * Returns the plain text, or the value unchanged if it was stored before encryption existed.
     * Returns null if it can't be decrypted (for example the key was wiped); the student then
     * simply registers again with the same roll number and keeps their history.
     */
    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val all = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (all.size <= IV_BYTES) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
            String(c.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
