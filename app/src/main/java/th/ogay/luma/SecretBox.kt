package th.ogay.luma

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps short secrets (API keys) encrypted with a key that never leaves the phone's key store. */
object SecretBox {
    private const val ALIAS = "lumora_secrets"
    private const val TAG = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun isSealed(s: String) = s.startsWith(TAG)

    /** The text encrypted, or the plain text when the key store is not available (so a key is never lost). */
    fun seal(plain: String): String = if (plain.isEmpty()) "" else runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        TAG + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrDefault(plain)

    /** The plain text of [s]; older values that were never sealed come back as they are. Empty when it cannot be opened. */
    fun open(s: String): String {
        if (!isSealed(s)) return s
        return runCatching {
            val (iv, ct) = s.removePrefix(TAG).split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct))
        }.getOrDefault("")
    }
}
