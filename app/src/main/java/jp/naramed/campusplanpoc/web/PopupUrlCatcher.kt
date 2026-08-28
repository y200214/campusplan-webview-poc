package jp.naramed.campusplanpoc.web

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * window.open で開かれようとした URL を捕まえるためだけの使い捨て WebViewClient。
 *
 * 仕組み:
 *   onCreateWindow で一時 WebView を渡すと、WebView がそこへ遷移しようとする。
 *   その最初の URL だけ受け取って、本体の WebView 側で開き直す。
 *   一時 WebView は中身を描画する前に破棄する。
 *
 * これにより「ポップアップを開かせない」まま、
 * 「ポップアップで開くはずだったページ」へは正規に遷移できる。
 * URL は本体側で allowlist を通してから読み込むので、外部サイトへは出られない。
 */
class PopupUrlCatcher(private val onUrl: (String) -> Unit) : WebViewClient() {

    private var captured = false

    private fun capture(url: String?, view: WebView) {
        if (captured) return
        captured = true
        url?.takeIf { it.isNotBlank() && it != "about:blank" }?.let(onUrl)
        // コールバックの中で直接 destroy するとクラッシュしうるので post する
        view.post { view.destroy() }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        capture(request.url?.toString(), view)
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        // shouldOverrideUrlLoading が呼ばれない経路のための保険
        capture(url, view)
    }
}
