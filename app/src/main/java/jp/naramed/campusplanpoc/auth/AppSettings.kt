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
    private const val KEY_CONFIRM_EXIT = "confirm_exit_on_return"
    private const val KEY_IDLE_TIMEOUT = "idle_timeout_seconds"

    /** 無操作タイムアウトの既定値（秒）。共用端末での実用値 */
    const val DEFAULT_IDLE_TIMEOUT_SECONDS = 90

    /** タイムアウトの選択肢。0 は無効 */
    val IDLE_TIMEOUT_CHOICES = listOf(0, 30, 60, 90, 180, 300)

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

    /**
     * 他のアプリへ移ったあと戻ってきたとき、終了するか確認するか。
     *
     * バックグラウンドへ移った「瞬間」にダイアログは出せない（もう前面にいない）。
     * そのため移ったことを記録しておき、戻ってきた時点で訊く。
     * 席を離れた隙に他人が触る状況を想定しているので、確認は戻り側で十分機能する。
     *
     * 既定は有効。共用端末で前の人のセッションが残るほうが危ないため。
     */
    fun confirmExitOnReturn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_EXIT, true)

    fun setConfirmExitOnReturn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_EXIT, enabled).apply()
    }

    /**
     * 無操作が続いたら自動でログアウトするまでの秒数。0 で無効。
     *
     * 共用端末で本当に効くのはこれ。
     * 「終わったらログアウトする」を利用者の記憶に頼ると、必ず押し忘れが出る。
     * 前の人の画面が残ったまま次の人が座る、という事故を仕組みで防ぐ。
     */
    fun idleTimeoutSeconds(context: Context): Int =
        prefs(context).getInt(KEY_IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT_SECONDS)

    fun setIdleTimeoutSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_IDLE_TIMEOUT, seconds).apply()
    }
}
