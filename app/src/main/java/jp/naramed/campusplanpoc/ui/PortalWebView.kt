package jp.naramed.campusplanpoc.ui

import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import jp.naramed.campusplanpoc.PortalConfig
import jp.naramed.campusplanpoc.model.NetworkObservation
import jp.naramed.campusplanpoc.model.PortalEvent
import jp.naramed.campusplanpoc.security.UrlPolicy
import jp.naramed.campusplanpoc.web.PageProbe
import jp.naramed.campusplanpoc.web.PortalBridge
import jp.naramed.campusplanpoc.web.PortalWebChromeClient
import jp.naramed.campusplanpoc.web.PortalWebViewClient
import jp.naramed.campusplanpoc.web.SyllabusFlow
import jp.naramed.campusplanpoc.web.WebViewSecurity

private const val TAG = "PortalWebView"

/**
 * 設定済みの WebView を 1 つだけ生成して保持する。
 *
 * WebView は Activity の Context を必要とするため ViewModel には置かない。
 * 画面回転では Activity を再生成しない設定（AndroidManifest の configChanges）にしてあるので、
 * この remember が保持されたままセッションも維持される。
 */
@Composable
fun rememberPortalWebView(
    viewModel: PortalViewModel,
    probe: PageProbe,
    bridge: PortalBridge,
    syllabusFlow: SyllabusFlow,
): WebView {
    val context = LocalContext.current

    return remember {
        val webView = WebView(context)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        // セキュリティ設定は WebView 生成直後、最初のロードより前に必ず適用する
        WebViewSecurity.harden(webView)

        // ページ→Kotlin の一方向チャネル。CampusPlan のオリジンにだけ注入される。
        val bridgeAttached = bridge.attach(webView)
        if (!bridgeAttached) {
            Log.w(TAG, "ブリッジ未接続。API 取得機能は使用できない")
        }

        webView.webViewClient = PortalWebViewClient(
            object : PortalWebViewClient.Callbacks {
                override fun onPageStarted(url: String?) {
                    viewModel.onPageStarted(url)
                    viewModel.onCanGoBackChanged(webView.canGoBack())
                    // 計装が有効なら、新しい文書にできるだけ早く入れ直す。
                    // スクリプト側が二重適用を防ぐので、ここと onPageFinished の両方で呼んでよい。
                    reinstallObserverIfEnabled(webView, viewModel, probe)
                }

                override fun onPageFinished(url: String?) {
                    viewModel.onPageFinished(url)
                    viewModel.onCanGoBackChanged(webView.canGoBack())
                    // ページ確定後に 1 回だけプローブする（読み取りのみ）
                    probe.run(webView) { viewModel.onProbeResult(it) }
                    // onPageStarted で入らなかった場合の保険
                    reinstallObserverIfEnabled(webView, viewModel, probe)
                    // シラバス取得待ちがあれば、参照画面に着いた時点で続きを行う
                    syllabusFlow.onPageFinished(webView, url)
                }

                override fun onUrlChanged(url: String?) {
                    viewModel.onUrlChanged(url)
                    viewModel.onCanGoBackChanged(webView.canGoBack())
                }

                override fun onEvent(event: PortalEvent) {
                    viewModel.onEvent(event)
                }

                override fun onExternalSubresourceObserved(host: String, blocked: Boolean) {
                    // ワーカースレッドから呼ばれる。ViewModel 側は StateFlow.update で受ける。
                    viewModel.onExternalSubresourceObserved(host, blocked)
                }

                override fun onNetworkObserved(observation: NetworkObservation) {
                    // 同じくワーカースレッドから呼ばれる
                    viewModel.onNetworkObserved(observation)
                }
            }
        )

        webView.webChromeClient = PortalWebChromeClient(
            onProgress = viewModel::onProgress,
            onTitle = viewModel::onTitle,
            onPopupUrl = { url ->
                // 捕まえた URL は必ず allowlist を通してから本体で開く
                webView.post { webView.loadAllowedUrl(url) }
            },
        )

        // ダウンロードは Phase 1 では一律ブロックする。
        // 端末のストレージにポータル上の資料が残るのは、
        // 病院用途では管理外のデータ持ち出しになりうるため。
        webView.setDownloadListener { url, _, _, _, _ ->
            Log.w(TAG, "ダウンロード要求をブロック: ${UrlPolicy.redactForLog(url)}")
            viewModel.onEvent(PortalEvent.DownloadBlocked(UrlPolicy.redactForLog(url)))
        }

        webView.loadAllowedUrl(PortalConfig.START_URL)
        webView
    }
}

/**
 * 計装が有効なら再導入する。
 *
 * 計装はページ内の JS を包むものなので、ページ遷移のたびに消える。
 * 「先に開始してから操作する」という手順を人間に守らせる設計は壊れやすいので、
 * 有効な間はアプリ側が自動で入れ直す。
 */
private fun reinstallObserverIfEnabled(
    webView: WebView,
    viewModel: PortalViewModel,
    probe: PageProbe,
) {
    if (!viewModel.state.value.netObserverEnabled) return
    probe.installNetObserver(webView) { /* 結果はログに出る */ }
}

/**
 * loadUrl は shouldOverrideUrlLoading を経由しないため、
 * アプリ側からロードする場合もここで allowlist を必ず通す（多層防御）。
 */
fun WebView.loadAllowedUrl(url: String) {
    when (val decision = UrlPolicy.decide(url)) {
        is UrlPolicy.Decision.Allow -> loadUrl(url)
        is UrlPolicy.Decision.Block ->
            Log.e(TAG, "allowlist 外のためロードしない: ${UrlPolicy.redactForLog(url)} (${decision.reason})")
    }
}
