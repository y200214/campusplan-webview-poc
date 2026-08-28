package jp.naramed.campusplanpoc.web

import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.net.Uri
import jp.naramed.campusplanpoc.BuildConfig

/**
 * 進捗表示と、ブラウザ的な追加機能の拒否を担当する WebChromeClient。
 *
 * 既定では「余計なことは何もさせない」方針。
 * Phase 3 以降で必要になった機能だけを、理由付きで個別に開放する。
 */
class PortalWebChromeClient(
    private val onProgress: (Int) -> Unit,
    private val onTitle: (String?) -> Unit,
    /** window.open が開こうとした URL。本体側で allowlist を通して開き直す */
    private val onPopupUrl: (String) -> Unit,
) : WebChromeClient() {

    companion object {
        private const val TAG = "PortalWebChrome"
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        onTitle(title)
    }

    /**
     * 新規ウィンドウ（target="_blank" / window.open）の扱い。
     *
     * 別ウィンドウとしては開かせない。ポップアップ用の WebView は
     * 別インスタンスになるため allowlist 制御をすり抜ける経路になりやすい。
     *
     * ただし単純に拒否すると、ポータルが正規の導線としてポップアップを使っている場合
     * （このポータルでは WebClass への遷移がそれ）に機能が使えなくなる。
     *
     * そこで「使い捨ての WebView に URL だけ受け取らせて即座に破棄し、
     * 本体の WebView で allowlist を通して開き直す」方式にする。
     *
     * isUserGesture が false のポップアップ（広告等の自動ポップアップ）は拒否する。
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        if (!isUserGesture) {
            Log.w(TAG, "ユーザー操作によらないポップアップを拒否")
            return false
        }

        val catcher = WebView(view.context)
        catcher.webViewClient = PopupUrlCatcher { url ->
            Log.d(TAG, "ポップアップの遷移先を捕捉")
            onPopupUrl(url)
        }

        val transport = resultMsg.obj as? WebView.WebViewTransport
        if (transport == null) {
            catcher.destroy()
            return false
        }
        transport.webView = catcher
        resultMsg.sendToTarget()
        return true
    }

    /** カメラ / マイク等のパーミッション要求は一律拒否 */
    override fun onPermissionRequest(request: PermissionRequest) {
        Log.w(TAG, "WebView からのパーミッション要求を拒否: ${request.resources.joinToString()}")
        request.deny()
    }

    /** 位置情報も拒否（retain=false で記憶もしない） */
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }

    /**
     * ファイル選択（<input type="file">）は Phase 1 では拒否。
     * 研修医評価フォームで添付が必要になった場合に、
     * ActivityResultContracts 経由で限定的に開放することを検討する。
     */
    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        Log.w(TAG, "ファイル選択要求をブロック")
        filePathCallback.onReceiveValue(null)
        return true
    }

    /**
     * JS コンソールは debug ビルドのみ、かつ内容を切り詰めて出す。
     * ページ内のエラーメッセージに個人情報が載ることがあるため、そのまま全文は出さない。
     */
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        if (BuildConfig.DEBUG) {
            val msg = message.message().orEmpty().take(160)
            Log.d(TAG, "[js:${message.messageLevel()}] $msg (line ${message.lineNumber()})")
        }
        return true
    }
}
