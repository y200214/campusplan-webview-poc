package jp.naramed.campusplanpoc.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * 共用タブレットを「このアプリしか使えない端末」にする。
 *
 * 使う仕組みは 2 つ。
 *  - **Lock Task Mode**: ホームキー・戻る・履歴を無効化し、アプリから出られなくする。
 *  - **既定のホームアプリ固定**: 端末を再起動してもこのアプリが立ち上がる。
 *
 * どちらも Device Owner が設定されている端末でのみ有効。設定されていない場合、
 * [startKiosk] は「画面固定」の弱い形（利用者が戻る＋履歴の長押しで抜けられる）
 * にとどまる。共用運用を本気でやるなら Device Owner を設定すること。
 *
 * 締め出し対策として [stopKiosk] を必ず用意し、管理者 PIN の先に置く。
 * 抜けられない端末を作ってしまうと、現場で詰む。
 */
object KioskManager {

    private const val TAG = "KioskManager"

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) =
        ComponentName(context, PortalDeviceAdminReceiver::class.java)

    /** Device Owner として設定済みか（＝本格的なキオスクにできるか） */
    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    /** いま画面固定中か */
    fun isLocked(activity: Activity): Boolean {
        val dpm = dpm(activity)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            dpm.isLockTaskPermitted(activity.packageName)
        }
    }

    /**
     * キオスクを開始する。
     *
     * Device Owner なら自分自身を許可リストに入れてから固定するので、
     * 確認ダイアログも出ないし抜けられない。
     * そうでない場合は通常の画面固定になり、利用者が抜けられる余地が残る。
     */
    fun startKiosk(activity: Activity) {
        runCatching {
            if (isDeviceOwner(activity)) {
                dpm(activity).setLockTaskPackages(
                    admin(activity),
                    arrayOf(activity.packageName),
                )
            }
            activity.startLockTask()
            Log.d(TAG, "キオスクを開始した（DeviceOwner=${isDeviceOwner(activity)}）")
        }.onFailure { Log.w(TAG, "キオスク開始に失敗: ${it.javaClass.simpleName}") }
    }

    /** キオスクを終了する。管理者 PIN の先に置くこと */
    fun stopKiosk(activity: Activity) {
        runCatching {
            activity.stopLockTask()
            Log.d(TAG, "キオスクを終了した")
        }.onFailure { Log.w(TAG, "キオスク終了に失敗: ${it.javaClass.simpleName}") }
    }

    /**
     * このアプリを既定のホームアプリにする。
     *
     * 端末を再起動しても、ホームキーを押しても、このアプリが出るようになる。
     * Device Owner のときだけ、利用者に選択させずに設定できる。
     */
    fun setAsHome(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.w(TAG, "Device Owner ではないのでホーム固定はできない")
            return
        }
        runCatching {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm(context).addPersistentPreferredActivity(
                admin(context),
                filter,
                ComponentName(context, "jp.naramed.campusplanpoc.MainActivity"),
            )
            Log.d(TAG, "既定のホームアプリに設定した")
        }.onFailure { Log.w(TAG, "ホーム固定に失敗: ${it.javaClass.simpleName}") }
    }

    /** ホーム固定を解除する */
    fun clearHome(context: Context) {
        if (!isDeviceOwner(context)) return
        runCatching {
            dpm(context).clearPackagePersistentPreferredActivities(
                admin(context),
                context.packageName,
            )
            Log.d(TAG, "ホーム固定を解除した")
        }
    }

    /**
     * Device Owner を返上する。
     *
     * これを呼ばないと、端末を初期化しない限り管理者設定が外れない。
     * 運用をやめるときの出口として必ず残しておく。
     */
    fun releaseDeviceOwner(context: Context) {
        if (!isDeviceOwner(context)) return
        runCatching {
            clearHome(context)
            dpm(context).clearDeviceOwnerApp(context.packageName)
            Log.d(TAG, "Device Owner を返上した")
        }.onFailure { Log.w(TAG, "返上に失敗: ${it.javaClass.simpleName}") }
    }
}
