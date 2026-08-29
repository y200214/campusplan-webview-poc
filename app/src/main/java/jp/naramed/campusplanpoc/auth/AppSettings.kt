package jp.naramed.campusplanpoc.auth

import android.content.Context

/**
 * 端末ごとの動作設定。
 *
 * 共用端末での利用を想定しているため、既定は「安全側」に倒す。
 */
object AppSettings {

    private const val PREFS = "campusplan_settings"
    private const val KEY_AUTO_LOGOUT = "auto_logout_on_launch"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * アプリを閉じたらログアウトするか。
     *
     * 実装は「起動のたびに前回の Cookie を捨てる」。終了時に消す方式は、
     * プロセスを強制終了されると走らないので取りこぼす。
     *
     * 既定は有効。共用端末で前の人のセッションが残っているほうが危ないため。
     * 個人端末で使う場合は切れる。
     */
    fun autoLogoutOnLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_LOGOUT, true)

    fun setAutoLogoutOnLaunch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_LOGOUT, enabled).apply()
    }
}
