package jp.naramed.campusplanpoc.model

/**
 * WebView 側で起きた、ユーザーに知らせるべき出来事。
 * UI にそのまま出せる粒度の情報だけを持たせる（URL はログ同様に秘匿済みのものを渡す）。
 */
sealed interface PortalEvent {

    /** allowlist 外への遷移をブロックした */
    data class NavigationBlocked(
        val redactedUrl: String,
        val reason: String,
    ) : PortalEvent

    /** SSL エラーが発生したので接続を中止した（無視して続行は絶対にしない） */
    data class SslErrorRejected(
        val redactedUrl: String,
        val detail: String,
    ) : PortalEvent

    /** HTTP エラー / ネットワークエラー */
    data class LoadError(
        val redactedUrl: String,
        val detail: String,
    ) : PortalEvent

    /** ダウンロード要求をブロックした */
    data class DownloadBlocked(
        val redactedUrl: String,
    ) : PortalEvent
}
