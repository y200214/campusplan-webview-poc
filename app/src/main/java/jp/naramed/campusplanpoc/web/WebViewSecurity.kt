package jp.naramed.campusplanpoc.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.util.Log
import androidx.webkit.WebViewCompat
import jp.naramed.campusplanpoc.BuildConfig

/**
 * WebView のセキュリティ設定を 1 箇所に集約する。
 *
 * ここを読めば「この WebView で何が許可されているか」がすべて分かる状態を維持すること。
 * 設定を緩める変更を入れる場合は、必ず理由をコメントに残す。
 */
object WebViewSecurity {

    private const val TAG = "WebViewSecurity"

    @SuppressLint("SetJavaScriptEnabled")
    fun harden(webView: WebView) {
        val s: WebSettings = webView.settings

        // --- JavaScript ---------------------------------------------------
        // CampusPlan は JavaScript 前提のポータルであり、また Phase 2 以降で
        // evaluateJavascript を使うため有効化が必要。
        // ただし「allowlist 内のホストしか開けない」ことと必ずセットで成立させる。
        s.javaScriptEnabled = true
        // ユーザー操作によらない自動ポップアップは禁止のまま。
        s.javaScriptCanOpenWindowsAutomatically = false
        // onCreateWindow を受け取るために true にする。
        // 別ウィンドウを実際に開くわけではなく、遷移先 URL を捕まえて
        // 本体の WebView で allowlist を通して開き直すため（PortalWebChromeClient 参照）。
        s.setSupportMultipleWindows(true)

        // --- ストレージ -----------------------------------------------------
        // 業務ポータルは sessionStorage / localStorage を使うことが多いので有効化する。
        s.domStorageEnabled = true

        // --- ローカルリソースへのアクセスは全面禁止 --------------------------
        // file:// を読めると、悪意あるページから端末内ファイルを読み出される典型的な穴になる。
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false

        // --- 混在コンテンツ禁止 ---------------------------------------------
        // https ページ内から http のサブリソースを読ませない。
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // --- そのほか不要機能の停止 -----------------------------------------
        s.setGeolocationEnabled(false)
        s.mediaPlaybackRequiresUserGesture = true
        @Suppress("DEPRECATION")
        s.saveFormData = false            // 入力内容（ID など）を WebView に保存させない
        @Suppress("DEPRECATION")
        s.databaseEnabled = false
        s.setSafeBrowsingEnabled(true)    // API 26+

        // User-Agent は書き換えない。
        // 偽装するとサーバー側のアクセスログ・監査ログの意味が変わってしまうため、
        // 「正規ブラウザとして正直に振る舞う」方針を守る。

        // --- Cookie / セッション --------------------------------------------
        val cookieManager = CookieManager.getInstance()
        // 正規ログインのセッション Cookie を保持するために必須。
        cookieManager.setAcceptCookie(true)
        // サードパーティ Cookie は既定で拒否。
        // 外部 IdP を経由する構成が判明した場合のみ、理由を明記して見直す。
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        // --- リモートデバッグ ------------------------------------------------
        // debug ビルドでのみ有効。release で有効にすると、USB 接続した PC から
        // ログイン済みセッションの DOM / Cookie を覗ける状態になる。
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        if (BuildConfig.DEBUG) {
            val pkg = WebViewCompat.getCurrentWebViewPackage(webView.context)
            Log.d(TAG, "WebView provider = ${pkg?.packageName} ${pkg?.versionName}")
        }
    }

    /**
     * Cookie を永続領域へ書き出す。
     * onPause / onStop のタイミングで呼び、プロセス終了時のセッション消失を防ぐ。
     */
    fun flushCookies() {
        CookieManager.getInstance().flush()
    }

    /**
     * 明示ログアウト用。Cookie とキャッシュを消す。
     *
     * 注意: これはアプリ側の後始末にすぎず、サーバー側のセッションは無効化されない。
     * 正しくログアウトするには、ポータルの正規ログアウト画面を経由させること。
     */
    fun clearLocalSession(webView: WebView) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
    }
}
