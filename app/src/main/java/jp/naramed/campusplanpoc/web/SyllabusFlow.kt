package jp.naramed.campusplanpoc.web

import android.util.Log
import android.webkit.WebView
import jp.naramed.campusplanpoc.PortalConfig
import jp.naramed.campusplanpoc.model.ApiResponse
import jp.naramed.campusplanpoc.security.UrlPolicy

/**
 * Phase 5: 講義コードからシラバスを取る一連の流れ。
 *
 * なぜ「遷移してから叩く」のか:
 *   SystemD Lead の RPC は画面（kinoId）ごとに発行されたトークンを検証する。
 *   時間割の一覧から直接 SyllabusSanshoWebApi を叩くと、そのとき表示している
 *   画面（例: シラバス検索）のコンテキストしか手元に無いため、サーバーに
 *     {"errorMessages":[{"message":"{0}の値が不正です。","args":["Token"]}]}
 *   で弾かれる（2026-08-28 実測）。
 *
 *   これは CSRF 保護が正しく効いている状態であり、迂回する対象ではない。
 *   正規の手順どおりシラバス参照画面を開いてページ自身にトークンを発行させ、
 *   そのうえで同じ API を呼ぶ。
 *
 * 遷移完了とトークン発行にはずれがあるが、待つ処理は
 * [PageFetcher] が流す js/syllabus_fetch.js 側に置いてある
 * （ページの中でしか観測できないため）。ここでは遷移の面倒だけ見る。
 */
class SyllabusFlow(private val fetcher: PageFetcher) {

    companion object {
        private const val TAG = "SyllabusFlow"
    }

    private data class Pending(
        val kogiCd: String,
        val nendo: String,
        val onResult: (ApiResponse) -> Unit,
    )

    /** 遷移完了を待っている取得要求。1 件だけ持つ。 */
    private var pending: Pending? = null

    /**
     * シラバス参照画面へ遷移し、読み込み完了後に取得する。
     * 必ずメインスレッドから呼ぶこと。
     */
    fun open(
        webView: WebView,
        kogiCd: String,
        nendo: String,
        onResult: (ApiResponse) -> Unit,
    ) {
        val url = PortalConfig.syllabusSanshoUrl(kogiCd, nendo)
        val decision = UrlPolicy.decide(url)
        if (decision is UrlPolicy.Decision.Block) {
            Log.e(TAG, "allowlist 外のため開かない: ${decision.reason}")
            onResult(ApiResponse(ok = false, error = "allowlist 外のため開けません（${decision.reason}）"))
            return
        }

        // 前の要求が残っていたら、呼び出し側に結末を返してから捨てる
        pending?.onResult?.invoke(
            ApiResponse(ok = false, error = "別の科目の取得に置き換えられました")
        )
        pending = Pending(kogiCd, nendo, onResult)

        Log.d(TAG, "シラバス参照画面へ遷移: kogiCd=$kogiCd nendo=$nendo")
        webView.loadUrl(url)
    }

    /**
     * ページ読み込み完了時に呼ぶ。
     * 待っている要求があり、かつシラバス参照画面に着いていれば取得へ進む。
     */
    fun onPageFinished(webView: WebView, url: String?) {
        val waiting = pending ?: return
        if (!PortalConfig.isSyllabusSanshoUrl(url)) return
        pending = null
        Log.d(TAG, "シラバス参照画面に到達。取得へ進む: kogiCd=${waiting.kogiCd}")
        fetcher.fetchSyllabus(webView, waiting.kogiCd, waiting.nendo, waiting.onResult)
    }

    /** 待機中の要求を破棄する（セッション破棄時など） */
    fun cancel() {
        pending = null
    }
}
