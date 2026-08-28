package jp.naramed.campusplanpoc.web

import android.content.res.AssetManager
import android.util.Log
import android.webkit.WebView
import jp.naramed.campusplanpoc.model.ApiResponse
import jp.naramed.campusplanpoc.security.UrlPolicy
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Phase 4: ログイン済みページのコンテキストから API を GET する。
 *
 * セキュリティ上の要点:
 *  - 認証を Android 側で再実装しない。ページの正規セッションを使う。
 *  - 叩ける URL は allowlist を通ったもの、かつ現在表示中のページと同一オリジンのものだけ。
 *  - スクリプトへ URL を渡すときは JSONObject.quote で **JS 文字列リテラルとして**
 *    エスケープしてから埋め込む。文字列連結で組み立てない。
 *  - GET のみ。状態を変える操作はここでは提供しない。
 */
class PageFetcher(private val assets: AssetManager) {

    companion object {
        private const val TAG = "PageFetcher"
        private const val SCRIPT = "js/fetch_api.js"
        private const val SCRIPT_COMPARE = "js/api_compare.js"
        private const val SCRIPT_SYLLABUS = "js/syllabus_fetch.js"

        /** ログへ出してはいけないキー */
        private val REDACT_KEYS = setOf(
            "token", "accessToken",
            "userId", "userName", "gakusekiNo", "kojinId", "kyoinCd",
            "narikawariUserId", "narikawariUserNm",
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 発行中のリクエスト。ブリッジからの応答を id で突き合わせる。 */
    private val pending = mutableMapOf<String, (ApiResponse) -> Unit>()

    /** 本文をログに出してよいリクエストの id（比較テストのみ） */
    private val bodyLoggingIds = mutableSetOf<String>()
    private var counter = 0

    private val templates = mutableMapOf<String, String>()

    private fun template(path: String): String = templates.getOrPut(path) {
        assets.open(path).bufferedReader().use { it.readText() }
    }

    /** PortalBridge のコールバックから呼ぶ。メインスレッドで呼ばれる。 */
    fun onBridgeMessage(raw: String) {
        val response = runCatching { json.decodeFromString<ApiResponse>(raw) }
            .onFailure { Log.w(TAG, "ブリッジ応答を解析できない: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return

        val callback = pending.remove(response.id)
        if (callback == null) {
            Log.w(TAG, "対応するリクエストが無い応答を破棄: id=${response.id}")
            return
        }
        Log.d(
            TAG,
            "fetch 結果: ok=${response.ok} status=${response.status} " +
                "type=${response.contentType} len=${response.body.length} " +
                "truncated=${response.truncated} ctx=${response.contextSource} " +
                "error=${response.error ?: "-"}"
        )
        // エラー応答は原因究明のため本文を出す。
        // 正常応答の本文は個人情報を含みうるのでログには出さない。
        response.tokenInfo?.let { t ->
            Log.d(
                TAG,
                "token: present=${t.present} len=${t.length} jsonQuoted=${t.jsonQuoted} " +
                    "jwt=${t.looksJwt} exp=${t.expEpoch} expired=${t.expired}"
            )
        }
        // シラバス取得の結末。guid は本文への一時ハンドルなので値そのものは出さない。
        response.syllabus?.let { s ->
            Log.d(
                TAG,
                "シラバス: kogiCd=${s.kogiCd} 名称=${s.kogiNm.ifEmpty { "-" }} " +
                    "init=${s.initStatus} guid=${if (s.guid.isEmpty()) "無し" else "取得"} " +
                    "errorMsg=${s.errorMsg ?: "-"} 未登録=${s.notRegistered} " +
                    "本文取得=${s.bodyFetched}"
            )
        }
        if (response.status >= 400) {
            Log.w(TAG, "エラー応答本文: ${response.body.take(200)}")
        }
        // 比較テストの結果はステータスの比較なので、全文をログに出す。
        // 通常の API 応答は個人情報を含みうるためログに出さない。
        if (bodyLoggingIds.remove(response.id)) {
            // 応答に認証コンテキストが載って返る可能性があるため、必ず秘匿してから出す
            redactJson(response.body).chunked(400).forEachIndexed { i, part ->
                Log.d(TAG, "BODY[$i] $part")
            }
        }
        callback(response)
    }

    /**
     * ログ出力前の秘匿処理。
     *
     * このシステムの RPC は応答にも entryContext を載せて返すことがある。
     * トークンや本人特定情報をログへ出さないよう、既知のキーを潰す。
     */
    private fun redactJson(body: String): String {
        if (body.isBlank()) return body
        return runCatching {
            when (val v = JSONTokener(body).nextValue()) {
                is JSONObject -> redactValue(v).toString()
                is JSONArray -> redactValue(v).toString()
                else -> body
            }
        }.getOrDefault("[JSON以外 length=${body.length}]")
    }

    private fun redactValue(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out ->
            value.keys().forEach { key ->
                out.put(key, if (key in REDACT_KEYS) "<redacted>" else redactValue(value.get(key)))
            }
        }
        is JSONArray -> JSONArray().also { out ->
            for (i in 0 until value.length()) out.put(redactValue(value.get(i)))
        }
        else -> value
    }

    /**
     * API を GET する。必ずメインスレッドから呼ぶこと。
     *
     * @param absoluteUrl allowlist 内の絶対 URL
     */
    fun get(webView: WebView, absoluteUrl: String, onResult: (ApiResponse) -> Unit) =
        run(webView, absoluteUrl, SCRIPT, logBody = false, onResult = onResult)

    /**
     * 401 の原因切り分け用に、3 方式を逐次比較する。
     * 結果はステータスコードの比較であり個人情報を含まないため、本文もログに出す。
     */
    fun compare(webView: WebView, absoluteUrl: String, onResult: (ApiResponse) -> Unit) =
        run(webView, absoluteUrl, SCRIPT_COMPARE, logBody = true, onResult = onResult)

    /**
     * 講義コードからシラバスを取得する。
     *
     * URL とリクエストボディはページの中で組み立てる。
     * 認証コンテキスト（token を含む）はページ内に留まり、Kotlin 側へは来ない。
     */
    fun fetchSyllabus(
        webView: WebView,
        kogiCd: String,
        nendo: String,
        onResult: (ApiResponse) -> Unit,
    ) {
        val pageUrl = webView.url
        if (!UrlPolicy.isAllowed(pageUrl)) {
            onResult(ApiResponse(ok = false, error = "現在のページが allowlist 外です"))
            return
        }
        val id = "req-${counter++}"
        pending[id] = onResult

        val script = template(SCRIPT_SYLLABUS)
            .replace("__KOGICD__", JSONObject.quote(kogiCd))
            .replace("__NENDO__", JSONObject.quote(nendo))
            .replace("__ID__", JSONObject.quote(id))

        // 応答は講義情報であって個人情報ではないため、秘匿処理を通したうえでログに出す
        bodyLoggingIds += id
        Log.d(TAG, "シラバス取得: kogiCd=$kogiCd nendo=$nendo id=$id")
        webView.evaluateJavascript(script, null)
    }

    private fun run(
        webView: WebView,
        absoluteUrl: String,
        scriptPath: String,
        logBody: Boolean,
        onResult: (ApiResponse) -> Unit,
    ) {
        val pageUrl = webView.url
        if (!UrlPolicy.isAllowed(pageUrl)) {
            onResult(ApiResponse(ok = false, error = "現在のページが allowlist 外です"))
            return
        }
        if (!UrlPolicy.isAllowed(absoluteUrl)) {
            onResult(ApiResponse(ok = false, error = "allowlist 外の URL は取得しません"))
            return
        }
        // 表示中ページと同一オリジンであること（別オリジンへ横断させない）
        if (UrlPolicy.hostOf(absoluteUrl) != UrlPolicy.hostOf(pageUrl)) {
            onResult(ApiResponse(ok = false, error = "表示中ページと別ホストのため取得しません"))
            return
        }

        val id = "req-${counter++}"
        if (logBody) bodyLoggingIds += id
        pending[id] = onResult

        // URL と id を JS の文字列リテラルとして安全に埋め込む
        val script = template(scriptPath)
            .replace("__URL__", JSONObject.quote(absoluteUrl))
            .replace("__ID__", JSONObject.quote(id))

        Log.d(TAG, "fetch 開始: ${UrlPolicy.redactForLog(absoluteUrl)} id=$id")
        webView.evaluateJavascript(script, null)
    }
}
