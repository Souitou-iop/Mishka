package top.yukonga.mishka.platform

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


internal class SecretStorageUnavailableException(cause: Throwable? = null) : IllegalStateException(
    "Android Keystore is unavailable",
    cause,
)

/**
 * 敏感值（当前仅 Tailscale auth key）的加密落地。
 *
 * 明文只驻留进程内存，密文（IV + ciphertext）存独立的 SharedPreferences；
 * 密钥由 Android Keystore 生成且不可导出。不用 EncryptedSharedPreferences：
 * androidx.security-crypto 已废弃，且它会在首次访问时做整表迁移/校验，这里只需要
 * 单点加解密，自持一把 AES-GCM key 即可，也避免了新依赖。
 *
 * Keystore 不可用（极少数被裁 ROM）时 [available] 为 false；调用方必须拒绝
 * 持久化敏感值，不能为了可用性回落到明文。
 */
internal class SecretStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val available: Boolean by lazy { ensureKey() != null }

    fun get(key: String): String {
        val encoded = prefs.getString(key, null) ?: return ""
        val key0 = ensureKey() ?: return ""
        return runCatching { decrypt(encoded, key0) }.getOrElse {
            // 密钥被系统清除（如用户清空凭据）或密文损坏：删除后当作未设置，避免反复抛错
            Log.w(TAG, "failed to decrypt secret '$key', dropping it", it)
            prefs.edit { remove(key) }
            ""
        }
    }

    fun put(key: String, value: String) {
        if (value.isEmpty()) {
            remove(key)
            return
        }
        val key0 = ensureKey() ?: throw SecretStorageUnavailableException()
        val encoded = runCatching { encrypt(value, key0) }
            .getOrElse { throw SecretStorageUnavailableException(it) }
        prefs.edit { putString(key, encoded) }
    }

    fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    private fun ensureKey(): SecretKey? {
        keyCache?.let { return it }
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateKey()
        }.getOrElse {
            Log.w(TAG, "keystore unavailable; refusing plaintext secret storage", it)
            null
        }?.also { keyCache = it }
    }

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }

    private fun encrypt(value: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String, key: SecretKey): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH) { "secret payload too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, payload, 0, IV_LENGTH),
        )
        return String(cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH), Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "SecretStore"
        const val PREFS_NAME = "mishka_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mishka_secret_store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128

        @Volatile
        var keyCache: SecretKey? = null
    }
}
