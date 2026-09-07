package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API キーなどの秘密情報を Android Keystore の鍵で暗号化して保存する。
 *
 *   平文 ──AES-256-GCM──▶ [IV 12B][暗号文+タグ] ──Base64──▶ SharedPreferences("secure_prefs")
 *
 * 鍵は Keystore から出ない。ファイルをコピーされても復号できない。
 * androidx.security:security-crypto は 1.1.0 で API が非推奨化されたため依存しない。
 *
 * 旧: settings.xml / openrouter_prefs.xml に平文で保存されていた。
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"
    private const val PREFS_NAME = "secure_prefs"
    private const val KEY_ALIAS = "kennys_secure_prefs_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256

    private val lock = Any()

    @Volatile
    private var cachedKey: SecretKey? = null

    fun getString(context: Context, key: String): String? {
        val encoded = prefs(context).getString(key, null) ?: return null
        return try {
            decrypt(encoded)
        } catch (e: Exception) {
            // 鍵が失われた (端末初期化 / Keystore 破損) 場合は値を捨てる
            Log.e(TAG, "decrypt failed: $key", e)
            null
        }
    }

    fun putString(context: Context, key: String, value: String?) {
        val editor = prefs(context).edit()
        if (value.isNullOrEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, encrypt(value))
        }
        editor.apply()
    }

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    fun contains(context: Context, key: String): Boolean {
        return prefs(context).contains(key)
    }

    /**
     * 平文 Prefs から一度だけ移行し、平文側を消す。
     * 既に secure 側に値がある場合は上書きしない (secure が正)。
     */
    fun migrateFromPlain(
        context: Context,
        plainPrefsName: String,
        plainKey: String,
        secureKey: String = plainKey
    ): Boolean {
        val plain = context.getSharedPreferences(plainPrefsName, Context.MODE_PRIVATE)
        val value = plain.getString(plainKey, null) ?: return false

        if (!contains(context, secureKey)) {
            putString(context, secureKey, value)
        }
        plain.edit().remove(plainKey).apply()
        Log.i(TAG, "migrated $plainPrefsName/$plainKey → secure")
        return true
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        val out = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(body, 0, out, iv.size, body.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        require(all.size > IV_BYTES) { "cipher text too short" }

        val iv = all.copyOfRange(0, IV_BYTES)
        val body = all.copyOfRange(IV_BYTES, all.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }

        synchronized(lock) {
            cachedKey?.let { return it }

            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
                cachedKey = it
                return it
            }

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(spec)
            val created = generator.generateKey()
            cachedKey = created
            return created
        }
    }
}
