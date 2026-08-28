package jp.naramed.campusplanpoc.security

import android.net.Uri
import jp.naramed.campusplanpoc.PortalConfig
import java.util.Locale

/**
 * URL の許可判定とログ用の秘匿処理をまとめたもの。
 *
 * 判定は WebView 側の挙動に依存させず、ここだけを見れば
 * 「何が許可されているか」が分かる状態を保つ。
 */
object UrlPolicy {

    /** 判定結果。ブロック時は理由を UI とログに出せるようにしておく。 */
    sealed interface Decision {
        data object Allow : Decision
        data class Block(val reason: String) : Decision
    }

    /** WebView 内部で使われる特殊 URL。遷移としては無害なので通す。 */
    private val INTERNAL_URLS = setOf("about:blank", "about:srcdoc")

    fun decide(url: String?): Decision {
        if (url.isNullOrBlank()) return Decision.Block("URL が空")
        if (url in INTERNAL_URLS) return Decision.Allow

        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return Decision.Block("URL を解析できない")

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return Decision.Block("スキームなし")

        // http / intent: / javascript: / file: / content: などはすべてここで落ちる。
        if (scheme !in PortalConfig.ALLOWED_SCHEMES) {
            return Decision.Block("許可されていないスキーム: $scheme")
        }

        val host = uri.host?.lowercase(Locale.ROOT)
            ?: return Decision.Block("ホストなし")

        // 完全一致のみ。前方/後方一致にすると
        // campusplanportal.naramed-u.ac.jp.example.com のような詐称ホストを通してしまう。
        if (host !in PortalConfig.ALLOWED_HOSTS) {
            return Decision.Block("allowlist 外のホスト: $host")
        }

        // ユーザー情報付き URL (https://user:pass@host/) はフィッシングの常套手段なので拒否
        if (uri.userInfo != null) {
            return Decision.Block("userInfo 付き URL は許可しない")
        }

        return Decision.Allow
    }

    fun isAllowed(url: String?): Boolean = decide(url) is Decision.Allow

    fun hostOf(url: String?): String? =
        runCatching { Uri.parse(url).host?.lowercase(Locale.ROOT) }.getOrNull()

    /**
     * ログ出力用に URL からクエリとフラグメントを落とす。
     *
     * セッション ID / 学籍番号 / 一時トークンがクエリに載る作りの
     * 業務システムは珍しくないため、logcat には scheme://host/path までしか出さない。
     */
    fun redactForLog(url: String?): String {
        if (url.isNullOrBlank()) return "(none)"
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return "(unparseable)"
        val base = "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"
        val hasSecrets = !uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()
        return if (hasSecrets) "$base?<redacted>" else base
    }

    /** 画面表示・ログ用にテキストを切り詰める。 */
    fun truncate(text: String?, max: Int = 64): String {
        val t = text.orEmpty()
        return if (t.length <= max) t else t.take(max) + "…"
    }
}
