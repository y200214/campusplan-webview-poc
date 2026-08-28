package jp.naramed.campusplanpoc.web

import android.content.res.AssetManager
import android.util.Log
import android.webkit.WebView
import jp.naramed.campusplanpoc.BuildConfig
import jp.naramed.campusplanpoc.model.PageSnapshot
import jp.naramed.campusplanpoc.model.PageStructure
import jp.naramed.campusplanpoc.model.TimeTable
import jp.naramed.campusplanpoc.security.UrlPolicy
import kotlinx.serialization.json.Json
import org.json.JSONTokener

/**
 * evaluateJavascript でページの状態と構造を読み取る。
 *
 * 設計上の要点:
 *  - addJavascriptInterface は使わない。
 *    あれは「ページ側の JS から Android のメソッドを呼べる」双方向の口を開けることになり、
 *    XSS や中間者に一段深い攻撃面を与える。
 *    ここは evaluateJavascript の戻り値を受け取る一方向だけで足りる。
 *  - スクリプトは assets に置き、動的な文字列連結でスクリプトを組み立てない
 *    （Kotlin 側の値を JS に埋め込む必要が出た場合は JSON エンコードして渡すこと）。
 *  - allowlist 外のページでは実行しない。信頼していないページに自前のスクリプトを流し込まない。
 */
class PageProbe(private val assets: AssetManager) {

    companion object {
        private const val TAG = "PageProbe"
        private const val TAG_STRUCT = "PageStructure"
        private const val SCRIPT_SNAPSHOT = "js/page_probe.js"
        private const val SCRIPT_STRUCTURE = "js/page_structure.js"
        private const val SCRIPT_TIMETABLE = "js/timetable.js"
        private const val SCRIPT_NET_INSTALL = "js/net_observer_install.js"
        private const val SCRIPT_NET_READ = "js/net_observer_read.js"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val scriptCache = mutableMapOf<String, String>()

    private fun script(path: String): String = scriptCache.getOrPut(path) {
        assets.open(path).bufferedReader().use { it.readText() }
    }

    /**
     * 軽量プローブ。ページ読み込みのたびに自動実行される。
     * 必ずメインスレッドから呼ぶこと。
     */
    fun run(webView: WebView, onResult: (PageSnapshot?) -> Unit) {
        evaluate(webView, SCRIPT_SNAPSHOT) { raw ->
            val snapshot = decode<PageSnapshot>(raw)
            if (snapshot != null) {
                // ログには件数だけ出す。タイトル本文や本文テキストは出さない。
                Log.d(
                    TAG,
                    "probe ok: path=${snapshot.path} titleLen=${snapshot.title.length} " +
                        "ready=${snapshot.readyState} pwd=${snapshot.passwordFieldCount} " +
                        "forms=${snapshot.formCount} frames=${snapshot.frameCount} links=${snapshot.linkCount}"
                )
            } else {
                Log.w(TAG, "プローブ結果を解析できなかった")
            }
            onResult(snapshot)
        }
    }

    /**
     * 構造取得。ユーザーがボタンを押したときだけ実行する（毎回走らせない）。
     *
     * ページ読み込み完了後にも DOM が増えることが実測で分かっているため、
     * 自動実行ではなく明示的なタイミングで取得する設計にしている。
     */
    fun runStructure(webView: WebView, onResult: (PageStructure?) -> Unit) {
        evaluate(webView, SCRIPT_STRUCTURE) { raw ->
            val structure = decode<PageStructure>(raw)
            if (structure != null) {
                Log.d(
                    TAG_STRUCT,
                    "structure ok: path=${structure.path} nav=${structure.navLinks.size} " +
                        "script=${structure.scriptLinks.size} forms=${structure.forms.size} " +
                        "buttons=${structure.buttons.size} truncated=${structure.truncated}"
                )
                if (BuildConfig.DEBUG) dumpToLogcat(structure)
            } else {
                Log.w(TAG_STRUCT, "構造の解析に失敗")
            }
            onResult(structure)
        }
    }

    /**
     * POST ボディ観測の計装をページへ導入する（debug 用）。
     * ページの XMLHttpRequest / fetch を包む。内容は改変しない。
     */
    fun installNetObserver(webView: WebView, onResult: (String) -> Unit) {
        evaluate(webView, SCRIPT_NET_INSTALL) { raw ->
            val text = unwrap(raw)
            Log.d("NetObserver", "install: $text")
            onResult(text.orEmpty())
        }
    }

    /** 計装が溜めたリクエスト記録を読み出す */
    fun readNetObserver(webView: WebView, onResult: (String) -> Unit) {
        evaluate(webView, SCRIPT_NET_READ) { raw ->
            val text = unwrap(raw).orEmpty()
            if (BuildConfig.DEBUG) {
                text.chunked(400).forEachIndexed { i, part -> Log.d("NetObserver", "REC[$i] $part") }
            }
            onResult(text)
        }
    }

    /** evaluateJavascript が返す JSON 文字列リテラルを 1 段はがす */
    private fun unwrap(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> v
                else -> raw
            }
        }.getOrNull()
    }

    /**
     * Phase 5: 履修時間割ページから時間割を構造化データとして取り出す。
     *
     * API（/portal/api/KogiJikanwari）は本人のセッションでも 401 になることを
     * 実測で確認済みのため、DOM の data-cp-kogicd を使う。
     */
    fun runTimeTable(webView: WebView, onResult: (TimeTable?) -> Unit) {
        evaluate(webView, SCRIPT_TIMETABLE) { raw ->
            val table = decode<TimeTable>(raw)
            if (table != null) {
                Log.d(
                    "TimeTable",
                    "取得: heading=${table.heading} コマ数=${table.entries.size} " +
                        "科目数=${table.distinctCourses.size} 表=${table.tables.size}"
                )
                if (BuildConfig.DEBUG) {
                    table.tables.forEach { g ->
                        Log.d("TimeTable", "GRID key=${g.key} col=${g.colHeaders}")
                        Log.d("TimeTable", "GRID row=${g.rowHeaders.map { it.rowIndex to it.text }}")
                    }
                    table.entries.forEach {
                        Log.d(
                            "TimeTable",
                            "ENTRY ${it.kogiCd} ${it.kogiNm} table=${it.tableKey} " +
                                "row=${it.rowIndex} col=${it.cellIndex}"
                        )
                    }
                }
            } else {
                Log.w("TimeTable", "時間割を解析できなかった")
            }
            onResult(table)
        }
    }

    /**
     * 構造を logcat へ出す（debug ビルドのみ）。
     *
     * 注意: href やリンクテキストには科目名などが含まれる。
     * これは調査に必要な情報だが、release ビルドでは出力されない。
     * logcat をそのまま第三者へ共有しないこと。
     */
    private fun dumpToLogcat(s: PageStructure) {
        Log.d(TAG_STRUCT, "===== STRUCTURE DUMP BEGIN =====")
        Log.d(TAG_STRUCT, "url=${s.url}")
        Log.d(TAG_STRUCT, "title=${s.title}")
        s.headings.forEach { Log.d(TAG_STRUCT, "H ${it.tag}: ${it.text}") }
        s.navLinks.forEachIndexed { i, l ->
            Log.d(TAG_STRUCT, "A[$i] \"${l.text}\" -> ${l.href}${if (l.id.isNotEmpty()) " #${l.id}" else ""}")
        }
        s.scriptLinks.forEachIndexed { i, l ->
            val extra = buildString {
                if (l.id.isNotEmpty()) append(" #${l.id}")
                if (l.onclick.isNotEmpty()) append(" onclick=${l.onclick}")
                if (l.data.isNotEmpty()) append(" data=${l.data}")
            }
            Log.d(TAG_STRUCT, "JS[$i] \"${l.text}\" raw=${l.rawHref}$extra")
        }
        s.forms.forEachIndexed { i, f ->
            Log.d(TAG_STRUCT, "F[$i] ${f.method.uppercase()} ${f.action} id=${f.id} name=${f.name}")
            f.fields.forEach { Log.d(TAG_STRUCT, "    field name=${it.name} type=${it.type} id=${it.id}") }
        }
        s.buttons.forEachIndexed { i, b ->
            Log.d(TAG_STRUCT, "B[$i] \"${b.text}\" id=${b.id} name=${b.name} onclick=${b.onclick}")
        }
        s.ajax?.let { a ->
            Log.d(TAG_STRUCT, "AJAX hasJQuery=${a.hasJQuery}")
            Log.d(TAG_STRUCT, "AJAX ajaxSetupHeaderNames=${a.ajaxSetupHeaderNames}")
            Log.d(TAG_STRUCT, "AJAX beforeSend=${a.beforeSendSource}")
            Log.d(TAG_STRUCT, "AJAX localStorageKeys=${a.localStorageKeys}")
            Log.d(TAG_STRUCT, "AJAX sessionStorageKeys=${a.sessionStorageKeys}")
        }
        s.handlerSources.forEach {
            Log.d(TAG_STRUCT, "FN ${it.name} = ${it.source}")
        }
        Log.d(TAG_STRUCT, "===== STRUCTURE DUMP END =====")
    }

    /** allowlist を確認したうえでスクリプトを実行する共通処理 */
    private fun evaluate(webView: WebView, scriptPath: String, onRaw: (String?) -> Unit) {
        val currentUrl = webView.url
        if (!UrlPolicy.isAllowed(currentUrl)) {
            // 想定外のホストが表示されている状態でスクリプトを流さない
            Log.w(TAG, "allowlist 外のページなので実行しない: ${UrlPolicy.redactForLog(currentUrl)}")
            onRaw(null)
            return
        }
        webView.evaluateJavascript(script(scriptPath)) { raw -> onRaw(raw) }
    }

    /**
     * evaluateJavascript の戻り値をデコードする。
     *
     * JS 側が JSON.stringify した文字列を返すため、
     * コールバックにはさらに JSON エンコードされた「文字列リテラル」が届く。
     * 例: "{\"title\":\"...\"}"
     * まず 1 段はがしてから data class にデコードする。
     */
    private inline fun <reified T> decode(raw: String?): T? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val unwrapped = when (val value = JSONTokener(raw).nextValue()) {
                is String -> value          // 通常はこちら
                else -> raw                 // JS がオブジェクトを直接返した場合の保険
            }
            json.decodeFromString<T>(unwrapped)
        }.onFailure {
            Log.w(TAG, "JSON デコード失敗: ${it.javaClass.simpleName}: ${it.message?.take(200)}")
        }.getOrNull()
    }
}
