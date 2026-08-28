package jp.naramed.campusplanpoc.model

/**
 * Phase 4: WebView が実際に発行したリクエストの観測結果。
 *
 * 目的は「正規の Web フロントエンドがどの通信方式を使っているか」を知ること。
 * 脆弱性探索ではなく、DOM スクレイピングより API 利用を優先するための調査。
 *
 * 秘密情報の扱い（重要）:
 *   - Authorization / Cookie ヘッダは **値を保持しない**。存在の有無だけを持つ。
 *     トークンをアプリ側に取り出すと「アプリが認証情報を保持する」ことになり、
 *     本 PoC の方針に反するため。
 *   - レスポンス本文はここでは扱わない（shouldInterceptRequest では取得できない）。
 */
data class NetworkObservation(
    val method: String,
    /** ホストを除いた path + query */
    val pathAndQuery: String,
    val isMainFrame: Boolean,
    /** JSON API らしさの判定に使う代表的なヘッダのみ */
    val accept: String = "",
    val contentType: String = "",
    val requestedWith: String = "",
    /** Authorization ヘッダが付いていたか。値は保持しない */
    val hasAuthorizationHeader: Boolean = false,
) {
    /** JSON API の可能性が高いか */
    val looksLikeApi: Boolean
        get() = accept.contains("json", ignoreCase = true) ||
            contentType.contains("json", ignoreCase = true) ||
            requestedWith.isNotEmpty() ||
            hasAuthorizationHeader ||
            method != "GET"
}
