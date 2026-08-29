package jp.naramed.campusplanpoc.auth

import android.content.Context
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
 * ポータルの資格情報を、端末の Keystore で暗号化して保管する。
 *
 * 設計上の要点:
 *  - 鍵は **Android Keystore の中で生成し、外へ出さない**。取り出せるのは暗号文だけなので、
 *    アプリの保管領域を丸ごとコピーされても、その端末の鍵が無ければ復号できない。
 *  - 保管は各端末に閉じる。**サーバへ集約しない**。
 *    300 人規模で配っても、パスワードが一箇所に集まる作りにはしない。
 *  - 平文のパスワードはログに出さない。ここでも呼び出し側でも。
 *
 * 注意:
 *  カードの IDm は照合用に平文で持つ。IDm は秘密ではない（NFC が読める端末なら
 *  誰でも読める）ので隠す意味がなく、隠したところで保護にならない。
 */
object CredentialStore {

    private const val TAG = "CredentialStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "campusplan_credential_key_v1"
    private const val PREFS = "campusplan_credential"

    private const val KEY_ID = "login_id"
    private const val KEY_PASSWORD = "password"
    private const val KEY_IDM = "card_idm"

    private const val GCM_TAG_BITS = 128

    data class Credential(val loginId: String, val password: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 登録済みか */
    fun hasCredential(context: Context): Boolean =
        prefs(context).contains(KEY_PASSWORD) && prefs(context).contains(KEY_IDM)

    /** 登録されているカードの IDm（照合用） */
    fun registeredIdm(context: Context): String? =
        prefs(context).getString(KEY_IDM, null)

    fun save(context: Context, idm: String, credential: Credential): Boolean = runCatching {
        val key = getOrCreateKey()

        fun enc(value: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            val bytes = c.doFinal(value.toByteArray(Charsets.UTF_8))
            // IV は暗号文ごとに変わるので一緒に保存する
            return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        prefs(context).edit()
            .putString(KEY_ID, enc(credential.loginId))
            .putString(KEY_PASSWORD, enc(credential.password))
            .putString(KEY_IDM, idm)
            .apply()
        Log.d(TAG, "資格情報を保存した（IDm と紐づけ）")
        true
    }.onFailure { Log.w(TAG, "保存に失敗: ${it.javaClass.simpleName}") }.getOrDefault(false)

    fun load(context: Context): Credential? = runCatching {
        val key = getOrCreateKey()

        fun dec(stored: String?): String? {
            val parts = stored?.split(":") ?: return null
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            return String(c.doFinal(body), Charsets.UTF_8)
        }

        val id = dec(prefs(context).getString(KEY_ID, null)) ?: return null
        val pw = dec(prefs(context).getString(KEY_PASSWORD, null)) ?: return null
        Credential(id, pw)
    }.onFailure { Log.w(TAG, "復号に失敗: ${it.javaClass.simpleName}") }.getOrNull()

    /** 登録を消す。ログアウトや利用者の意思で呼ぶ */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
        Log.d(TAG, "資格情報を削除した")
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
