package jp.naramed.campusplanpoc.web

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * ページ側 JS から Kotlin へ結果を返すための一方向チャネル。
 *
 * なぜ addJavascriptInterface を使わないのか:
 *   addJavascriptInterface は「任意のオリジンのページ」から Android のメソッドを
 *   呼べる口を開けてしまう。オリジンを絞る仕組みが無い。
 *
 * ここで使う WebMessageListener の利点:
 *   - allowedOriginRules で **CampusPlan のオリジンだけ** に注入を限定できる
 *   - 公開しているのは postMessage 1 つだけ（メソッド最小化）
 *   - 受け取るのは文字列だけ。こちらから JS を呼び返す機能は使わない
 *
 * 受信データの扱い:
 *   届くのは「ページが返してきたデータ」であって命令ではない。
 *   文字列として解釈するだけで、eval したり、指示として実行したりしない。
 */
class PortalBridge(private val onMessage: (String) -> Unit) {

    companion object {
        private const val TAG = "PortalBridge"

        /** ページ側から見えるオブジェクト名。衝突しにくい名前にする。 */
        const val JS_OBJECT_NAME = "__campusPlanPocBridge"

        /** 注入を許可するオリジン。allowlist と同じ範囲に限定する。 */
        private val ALLOWED_ORIGIN_RULES = setOf("https://campusplanportal.naramed-u.ac.jp")
    }

    /** @return 接続できたか（端末の WebView が対応していない場合は false） */
    fun attach(webView: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "この WebView は WebMessageListener 非対応。API 取得機能は使えない")
            return false
        }

        WebViewCompat.addWebMessageListener(
            webView,
            JS_OBJECT_NAME,
            ALLOWED_ORIGIN_RULES,
        ) { _, message, sourceOrigin: Uri, isMainFrame, _ ->
            // 二重チェック: フレーム内やオリジン違いからの投稿は受け取らない
            if (!isMainFrame) return@addWebMessageListener
            if (sourceOrigin.toString() !in ALLOWED_ORIGIN_RULES) {
                Log.w(TAG, "想定外のオリジンからのメッセージを無視: $sourceOrigin")
                return@addWebMessageListener
            }
            message.data?.let(onMessage)
        }
        Log.d(TAG, "WebMessageListener を登録した")
        return true
    }
}
