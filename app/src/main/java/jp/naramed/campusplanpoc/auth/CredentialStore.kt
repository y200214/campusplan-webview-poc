package jp.naramed.campusplanpoc.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 学生証と紐づけたログイン情報を、端末の Keystore で暗号化して保管する。
 *
 * **1 台に複数枚を登録できる。** 共用の端末で複数人が使う想定があるため、
 * カード（IDm）ごとに 1 件の登録を持ち、タッチされた IDm で引く。
 *
 * 設計上の要点:
 *  - 鍵は Android Keystore の中で生成し、外へ出さない。暗号文をコピーされても
 *    その端末の鍵が無ければ復号できない。
 *  - サーバへは送らない。各端末に閉じるので、パスワードが一箇所に集まらない。
 *  - IDm は照合キーとして平文で持つ。秘密ではない（NFC が読める端末なら誰でも
 *    読める）ので、隠しても保護にならない。
 *  - 平文のパスワードはログに出さない。ここでも呼び出し側でも。
 */
object CredentialStore {

    private const val TAG = "CredentialStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "campusplan_credential_key_v1"
    private const val PREFS = "campusplan_credential"

    /** 登録一覧（JSON 配列）。要素は {idm, label, id, pw} */
    private const val KEY_ENTRIES = "entries_v2"

    private const val GCM_TAG_BITS = 128

    data class Credential(val loginId: String, val password: String)

    /** 画面に出す登録情報。パスワードは含めない */
    data class Entry(
        val idm: String,
        /** 利用者が付けた名前。空なら ID を表示に使う */
        val label: String,
        val loginId: String,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readArray(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_ENTRIES, "[]")) }
            .getOrDefault(JSONArray())

    private fun writeArray(context: Context, array: JSONArray) {
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    /** 登録の一覧。パスワードは復号しないので、一覧表示は認証不要で出せる */
    fun entries(context: Context): List<Entry> {
        val array = readArray(context)
        val key = runCatching { getOrCreateKey() }.getOrNull()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val idm = o.optString("idm").ifEmpty { return@mapNotNull null }
            // ログイン ID は表示に使うので復号する。失敗しても一覧は壊さない
            val loginId = key?.let { runCatching { decrypt(it, o.optString("id")) }.getOrNull() }
            Entry(
                idm = idm,
                label = o.optString("label"),
                loginId = loginId.orEmpty(),
            )
        }
    }

    fun isRegistered(context: Context, idm: String): Boolean =
        entries(context).any { it.idm.equals(idm, ignoreCase = true) }

    fun hasAny(context: Context): Boolean = readArray(context).length() > 0

    /**
     * 登録する。同じ IDm が既にあれば置き換える。
     * @return 成功したか
     */
    fun save(context: Context, idm: String, label: String, credential: Credential): Boolean =
        runCatching {
            val key = getOrCreateKey()
            val array = readArray(context)

            // 同じカードの既存登録を除く
            val kept = JSONArray()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                if (!o.optString("idm").equals(idm, ignoreCase = true)) kept.put(o)
            }
            kept.put(
                JSONObject().apply {
                    put("idm", idm)
                    put("label", label)
                    put("id", encrypt(key, credential.loginId))
                    put("pw", encrypt(key, credential.password))
                }
            )
            writeArray(context, kept)
            Log.d(TAG, "登録した（合計 ${kept.length()} 件）")
            true
        }.onFailure { Log.w(TAG, "保存に失敗: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** タッチされたカードに対応する資格情報を取り出す */
    fun load(context: Context, idm: String): Credential? = runCatching {
        val key = getOrCreateKey()
        val array = readArray(context)
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (!o.optString("idm").equals(idm, ignoreCase = true)) continue
            val id = decrypt(key, o.optString("id")) ?: return null
            val pw = decrypt(key, o.optString("pw")) ?: return null
            return Credential(id, pw)
        }
        null
    }.onFailure { Log.w(TAG, "復号に失敗: ${it.javaClass.simpleName}") }.getOrNull()

    /** 1 件だけ消す */
    fun remove(context: Context, idm: String) {
        val array = readArray(context)
        val kept = JSONArray()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (!o.optString("idm").equals(idm, ignoreCase = true)) kept.put(o)
        }
        writeArray(context, kept)
        Log.d(TAG, "1 件削除した（残り ${kept.length()} 件）")
    }

    /** 全部消す */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
        Log.d(TAG, "登録をすべて削除した")
    }

    // --- 暗号化 -------------------------------------------------------------

    private fun encrypt(key: SecretKey, value: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val bytes = c.doFinal(value.toByteArray(Charsets.UTF_8))
        // IV は暗号文ごとに変わるので一緒に保存する
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(key: SecretKey, stored: String?): String? {
        val parts = stored?.split(":") ?: return null
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(c.doFinal(body), Charsets.UTF_8)
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
