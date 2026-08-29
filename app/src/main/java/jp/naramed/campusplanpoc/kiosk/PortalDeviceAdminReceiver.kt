package jp.naramed.campusplanpoc.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 端末専用モード（Dedicated Device）にするための受け口。
 *
 * このアプリを Device Owner として設定すると、Lock Task Mode（画面固定）を
 * プログラムから制御できるようになり、ホームアプリとしても固定できる。
 * 共用タブレットを「このアプリしか使えない端末」にするための仕組み。
 *
 * 設定方法（初期化直後の端末でのみ可能）:
 *   1. タブレットを初期化し、**Google アカウントを追加しない**まま設定を終える
 *   2. 開発者向けオプションで USB デバッグを有効化
 *   3. PC から次を実行する
 *        adb shell dpm set-device-owner \
 *          jp.naramed.campusplanpoc/jp.naramed.campusplanpoc.kiosk.PortalDeviceAdminReceiver
 *
 * 注意: アカウントが 1 つでも登録されていると設定できない。
 * 解除するには [KioskManager.releaseDeviceOwner] を呼ぶか、端末を初期化する。
 */
class PortalDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d(TAG, "デバイス管理者として有効になった")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d(TAG, "デバイス管理者を解除された")
    }

    companion object {
        private const val TAG = "PortalDeviceAdmin"
    }
}
