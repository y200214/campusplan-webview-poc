package jp.naramed.campusplanpoc.auth

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 共用端末で、登録の削除・編集を管理者だけに限るための PIN。
 *
 * これは何であって、何でないか（重要）:
 *  - **端末上の誤操作・いたずらを防ぐための鍵**である。共用端末に学生が触れる前提で、
 *    「他人の登録を勝手に消せない」ようにするのが目的。
 *  - **サーバ側の権限管理ではない**。アプリを作り直したり端末のデータを消したりすれば
 *    突破できる。本当の権限制御が要るならサーバが判定するしかないが、
 *    CampusPlan 側は変更できないので、ここは端末内の運用上の歯止めに留まる。
 *  - 「学生か教員か」を選ばせるだけの方式は採らない。選ぶだけなら誰でも教員を選べて、
 *    守っているつもりで何も守れていない状態になるため。
 *
 * 保管:
 *  PIN そのものは保存しない。端末ごとのランダムなソルトと合わせて SHA-256 でハッシュし、
 *  その結果だけを保存する。総当たりは 4 桁なら現実的に可能なので、
 *  「秘密を守る」ものではなく「うっかり触らせない」ものと位置づける。
 */
object AdminLock {

    private const val PREFS = "campusplan_admin"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** PIN が設定されているか。未設定なら誰でも削除できる（個人端末での利用を邪魔しない） */
    fun isEnabled(context: Context): Boolean = prefs(context).contains(KEY_HASH)

    /** PIN を設定・変更する */
    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, hash(pin, salt))
            .apply()
    }

    /** PIN を外す。外すには現在の PIN が要る（呼び出し側で [verify] を通すこと） */
    fun clearPin(context: Context) {
        prefs(context).edit().remove(KEY_HASH).remove(KEY_SALT).apply()
    }

    fun verify(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(KEY_HASH, null) ?: return true
        val saltText = prefs(context).getString(KEY_SALT, null) ?: return false
        val salt = Base64.decode(saltText, Base64.NO_WRAP)
        return constantTimeEquals(stored, hash(pin, salt))
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    /** 比較時間から中身を推測されないようにする */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
