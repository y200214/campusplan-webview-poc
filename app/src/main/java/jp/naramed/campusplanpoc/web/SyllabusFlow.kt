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
     * いまどの段階を待っているか。
     *
     * ポータル（/portal/）と CampusPlan Smart（/cpsmart/）は **セッションが別**。
     * /portal/ にログインしただけでは /cpsmart/ は未認証で、参照画面の URL を
     * 直接開いても cpsmart のログイン画面へ飛ばされる（2026-08-29 実測）。
     * そのため、未認証なら先に SSO の入口を踏んでから参照画面へ進む。
     */
    private enum class Stage { NONE, SSO, SANSHO }

    private var stage = Stage.NONE

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

        // cpsmart に入れている（かつログイン画面ではない）ならそのまま参照画面へ
        val onCpsmart = PortalConfig.isCpsmartUrl(webView.url) &&
            !PortalConfig.isCpsmartLoginUrl(webView.url)
        if (onCpsmart) {
            stage = Stage.SANSHO
            Log.d(TAG, "シラバス参照画面へ遷移: kogiCd=$kogiCd nendo=$nendo")
            webView.loadUrl(url)
        } else {
            stage = Stage.SSO
            Log.d(TAG, "cpsmart 未認証。先に SSO を通す: kogiCd=$kogiCd")
            webView.loadUrl(PortalConfig.absoluteUrl(PortalConfig.CPSMART_SSO_PATH))
        }
    }

    /**
     * ページ読み込み完了時に呼ぶ。
     * SSO → 参照画面 の順に進み、着いたら取得へ移る。
     */
    fun onPageFinished(webView: WebView, url: String?) {
        val waiting = pending ?: return

        // cpsmart のログイン画面に飛ばされた＝ポータル側のセッションも切れている
        if (PortalConfig.isCpsmartLoginUrl(url)) {
            Log.w(TAG, "cpsmart のログイン画面へ飛ばされた。SSO できていない")
            pending = null
            stage = Stage.NONE
            waiting.onResult(
                ApiResponse(ok = false, error = "ポータルのセッションが切れています。ログインし直してください")
            )
            return
        }

        when (stage) {
            Stage.SSO -> {
                if (!PortalConfig.isCpsmartUrl(url)) return
                stage = Stage.SANSHO
                Log.d(TAG, "SSO 完了。シラバス参照画面へ遷移: kogiCd=${waiting.kogiCd}")
                webView.loadUrl(PortalConfig.syllabusSanshoUrl(waiting.kogiCd, waiting.nendo))
            }

            Stage.SANSHO -> {
                if (!PortalConfig.isSyllabusSanshoUrl(url)) return
                pending = null
                stage = Stage.NONE
                Log.d(TAG, "シラバス参照画面に到達。取得へ進む: kogiCd=${waiting.kogiCd}")
                fetcher.fetchSyllabus(webView, waiting.kogiCd, waiting.nendo, waiting.onResult)
            }

            Stage.NONE -> Unit
        }
    }

    /**
     * 遷移せず、いま表示している参照画面のトークンでそのまま取得する。
     *
     * トークンは画面（kinoId=3000230）に対して発行される。講義コードごとに
     * 発行し直す必要が無ければ、参照画面に一度入ったあとは何科目でも
     * ここから叩ける。全科目をまとめて取るときの主経路。
     *
     * 使い回しが通らなかった場合は応答の [ApiResponse.syllabus] の
     * tokenRejected が立つので、呼び出し側で [open] に切り替えて再試行すること。
     *
     * @return 参照画面に居なかった場合は false（呼び出し側は [open] を使う）
     */
    fun fetchHere(
        webView: WebView,
        kogiCd: String,
        nendo: String,
        onResult: (ApiResponse) -> Unit,
    ): Boolean {
        if (!PortalConfig.isSyllabusSanshoUrl(webView.url)) return false
        Log.d(TAG, "遷移せず取得（トークン使い回し）: kogiCd=$kogiCd")
        fetcher.fetchSyllabus(webView, kogiCd, nendo, onResult)
        return true
    }

    /** 待機中の要求を破棄する（セッション破棄時など） */
    fun cancel() {
        pending = null
        stage = Stage.NONE
    }
}
