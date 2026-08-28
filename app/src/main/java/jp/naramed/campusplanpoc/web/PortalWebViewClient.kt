package jp.naramed.campusplanpoc.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import jp.naramed.campusplanpoc.BuildConfig
import jp.naramed.campusplanpoc.PortalConfig
import jp.naramed.campusplanpoc.model.NetworkObservation
import jp.naramed.campusplanpoc.model.PortalEvent
import jp.naramed.campusplanpoc.security.UrlPolicy
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * ナビゲーション制御とエラー処理を担当する WebViewClient。
 *
 * 責務:
 *  - allowlist 外のメインフレーム遷移をブロックする
 *  - SSL エラーを絶対に握りつぶさない
 *  - allowlist 外ホストへのサブリソース要求を記録する（Phase 4 の下調べも兼ねる）
 */
class PortalWebViewClient(
    private val callbacks: Callbacks,
) : WebViewClient() {

    interface Callbacks {
        fun onPageStarted(url: String?)
        fun onPageFinished(url: String?)
        fun onUrlChanged(url: String?)
        fun onEvent(event: PortalEvent)

        /**
         * allowlist 外ホストへのサブリソース要求を観測した。
         * 注意: この呼び出しはワーカースレッドから来る。実装側でスレッド安全にすること。
         */
        fun onExternalSubresourceObserved(host: String, blocked: Boolean)

        /**
         * Phase 4: allowlist 内ホストへのリクエストのうち、API らしきものを観測した。
         * これもワーカースレッドから呼ばれる。
         */
        fun onNetworkObserved(observation: NetworkObservation)
    }

    companion object {
        private const val TAG = "PortalWebViewClient"

        private val STATIC_SUFFIXES = listOf(
            ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
            ".woff", ".woff2", ".ttf", ".eot", ".map",
        )
    }

    /**
     * Phase 4: allowlist 内のリクエストのうち、API らしきものを記録する。
     *
     * これは通常のブラウザ操作で発生する通信を観測しているだけで、
     * 追加のリクエストを発行したり、内容を書き換えたりはしない。
     */
    private fun observeIfInteresting(request: WebResourceRequest) {
        val uri = request.url ?: return
        val path = uri.path.orEmpty()
        if (isStaticAsset(path)) return

        val headers = request.requestHeaders.orEmpty()

        // ヘッダ名は大文字小文字が一定しないので正規化して引く
        fun header(name: String): String =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

        val observation = NetworkObservation(
            method = request.method ?: "GET",
            pathAndQuery = path + (uri.query?.let { "?" + it } ?: ""),
            isMainFrame = request.isForMainFrame,
            accept = header("Accept").take(80),
            contentType = header("Content-Type").take(80),
            requestedWith = header("X-Requested-With").take(60),
            // 値は取らない。存在の有無だけ。
            hasAuthorizationHeader = header("Authorization").isNotEmpty(),
        )

        if (!observation.looksLikeApi && !observation.isMainFrame) return

        if (BuildConfig.DEBUG) {
            Log.d(
                "PortalNetwork",
                "${observation.method} ${observation.pathAndQuery}" +
                    (if (observation.accept.isNotEmpty()) " accept=${observation.accept}" else "") +
                    (if (observation.requestedWith.isNotEmpty()) " xrw=${observation.requestedWith}" else "") +
                    (if (observation.hasAuthorizationHeader) " [Authorization有り]" else "")
            )
        }
        callbacks.onNetworkObserved(observation)
    }

    // ---- メインフレームの遷移制御 -------------------------------------------

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url?.toString()

        // サブフレーム内の遷移はここでは判定せず、shouldInterceptRequest 側に任せる。
        if (!request.isForMainFrame) return false

        return when (val decision = UrlPolicy.decide(url)) {
            is UrlPolicy.Decision.Allow -> {
                // false = WebView に通常どおりロードさせる
                false
            }

            is UrlPolicy.Decision.Block -> {
                Log.w(TAG, "遷移をブロック: ${UrlPolicy.redactForLog(url)} (${decision.reason})")
                callbacks.onEvent(
                    PortalEvent.NavigationBlocked(
                        redactedUrl = UrlPolicy.redactForLog(url),
                        reason = decision.reason,
                    )
                )
                // true = アプリが処理した扱いにして、WebView にはロードさせない。
                //
                // ここで Intent を投げて外部ブラウザに渡す実装にはしていない。
                // 正規のログインフローで必要な外部ホストが判明するまでは
                // 「黙って外へ出す」より「止めて理由を見せる」方が安全なため。
                true
            }
        }
    }

    // ---- サブリソース（画像 / CSS / JS / XHR）の観測と任意ブロック ------------

    /** 画像 / CSS / JS など、調査対象にする必要のない静的アセット */
    private fun isStaticAsset(path: String): Boolean {
        val p = path.lowercase(Locale.ROOT)
        return STATIC_SUFFIXES.any { p.endsWith(it) }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url?.toString() ?: return null

        // data: / blob: / about: などはネットワーク要求ではないので判定対象外にする
        // （インライン画像を誤ってブロックしないため）
        val scheme = request.url?.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https" && scheme != "http") return null

        val decision = UrlPolicy.decide(url)
        if (decision is UrlPolicy.Decision.Allow) {
            observeIfInteresting(request)
            return null
        }

        val host = UrlPolicy.hostOf(url) ?: "(unknown)"
        val block = PortalConfig.BLOCK_NON_ALLOWLISTED_SUBRESOURCES

        // 観測結果は allowlist を実測ベースで確定させるための材料になる。
        callbacks.onExternalSubresourceObserved(host, block)

        if (BuildConfig.DEBUG) {
            val verb = if (block) "ブロック" else "許可(観測のみ)"
            Log.d(TAG, "allowlist 外サブリソースを$verb: ${UrlPolicy.redactForLog(url)}")
        }

        if (!block) return null

        // 空レスポンスを返してリクエスト自体を成立させない。
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked by app allowlist",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    // ---- ライフサイクル ------------------------------------------------------

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        Log.d(TAG, "onPageStarted: ${UrlPolicy.redactForLog(url)}")
        callbacks.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        Log.d(TAG, "onPageFinished: ${UrlPolicy.redactForLog(url)}")
        callbacks.onPageFinished(url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        // pushState 等でページ遷移せずに URL だけ変わるケースを拾う
        callbacks.onUrlChanged(url)
    }

    // ---- エラー処理 ----------------------------------------------------------

    /**
     * SSL エラー。
     *
     * 絶対に handler.proceed() を呼ばないこと。
     * proceed() は証明書検証の失敗を無視して通信を続けることを意味し、
     * 中間者攻撃に対して無防備になる。ここでは必ず cancel() する。
     */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val detail = when (error.primaryError) {
            SslError.SSL_EXPIRED -> "証明書の有効期限切れ"
            SslError.SSL_IDMISMATCH -> "ホスト名不一致"
            SslError.SSL_UNTRUSTED -> "信頼できない認証局"
            SslError.SSL_DATE_INVALID -> "証明書の日付が不正"
            SslError.SSL_NOTYETVALID -> "証明書がまだ有効でない"
            SslError.SSL_INVALID -> "証明書が不正"
            else -> "SSL エラー (code=${error.primaryError})"
        }
        Log.e(TAG, "SSL エラーのため接続を中止: ${UrlPolicy.redactForLog(error.url)} / $detail")
        handler.cancel()
        callbacks.onEvent(
            PortalEvent.SslErrorRejected(UrlPolicy.redactForLog(error.url), detail)
        )
    }

    /**
     * HTTP Basic / Digest 認証のチャレンジ。
     *
     * アプリ側で資格情報を扱わない方針なので、ここでは常に cancel する。
     * （WebView の既定ダイアログを出す実装にすると、アプリがパスワードを
     *   受け取る経路を作ってしまうため避ける）
     * 実際に Basic 認証が必要な構成だと判明した場合は、
     * 別途「WebView 標準の認証ダイアログに任せる」形を検討する。
     */
    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        Log.w(TAG, "HTTP 認証要求を拒否: host=$host")
        handler.cancel()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        val detail = "code=${error.errorCode} ${error.description}"
        Log.w(TAG, "読み込みエラー: ${UrlPolicy.redactForLog(request.url?.toString())} / $detail")
        callbacks.onEvent(
            PortalEvent.LoadError(UrlPolicy.redactForLog(request.url?.toString()), detail)
        )
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (!request.isForMainFrame) return
        val detail = "HTTP ${errorResponse.statusCode}"
        Log.w(TAG, "HTTP エラー: ${UrlPolicy.redactForLog(request.url?.toString())} / $detail")
        callbacks.onEvent(
            PortalEvent.LoadError(UrlPolicy.redactForLog(request.url?.toString()), detail)
        )
    }
}
