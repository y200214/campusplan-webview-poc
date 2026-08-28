package jp.naramed.campusplanpoc.model

/**
 * ログイン状態の推定結果。
 *
 * 重要: これは「アプリ側の見え方」でしかなく、認可の判断材料ではない。
 * 実際にデータを見せてよいかどうかを決めるのは常にサーバー側であり、
 * アプリはその結果を表示するだけ。この enum で権限判定を行ってはならない。
 */
enum class SessionState {
    /** まだページを読めていない / 判定材料が足りない */
    UNKNOWN,

    /** ログインフォームが見えている状態（＝ユーザー本人の入力待ち） */
    LOGIN_REQUIRED,

    /** ログイン後とみられる状態（あくまで推定） */
    LOGGED_IN_PROBABLE,
}

/**
 * Phase 1 の暫定ヒューリスティック。
 *
 * CampusPlan 固有の DOM をまだ知らないので、汎用シグナルのみで判定する:
 *  - password 入力欄があれば必ずログイン画面
 *  - allowlist 内ホストで、描画が完了していて、本文が一定量あればログイン後とみなす
 *
 * 実際のログイン後 DOM が分かった段階（Phase 2）で、
 * ログアウトリンクの有無・特定のメニュー要素の有無など、より確実な条件に差し替える。
 */
object LoginHeuristics {

    private const val MIN_TEXT_LENGTH_FOR_CONTENT = 40

    fun estimate(snapshot: PageSnapshot?, hostAllowed: Boolean): SessionState {
        if (snapshot == null || !hostAllowed) return SessionState.UNKNOWN
        if (snapshot.passwordFieldCount > 0) return SessionState.LOGIN_REQUIRED
        if (snapshot.readyState != "complete") return SessionState.UNKNOWN

        // frameset ページは body.innerText がほぼ空になるため、
        // フレームがある場合は本文量の条件を課さない。
        val looksLikeContent =
            snapshot.frameCount > 0 || snapshot.textLength >= MIN_TEXT_LENGTH_FOR_CONTENT

        return if (looksLikeContent) SessionState.LOGGED_IN_PROBABLE else SessionState.UNKNOWN
    }
}
