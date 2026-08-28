package jp.naramed.campusplanpoc

import jp.naramed.campusplanpoc.model.ApiEndpoint
import jp.naramed.campusplanpoc.model.PortalShortcut

/**
 * PoC 対象ポータルの設定。
 *
 * ここに書いてよいのは「どのホストを開くか」だけ。
 * ID / パスワード / トークンの類は絶対にここへ書かない（ハードコード禁止）。
 */
object PortalConfig {

    /** 起動時に開く URL。ログインはこのページ上でユーザー本人が行う。 */
    const val START_URL = "https://campusplanportal.naramed-u.ac.jp/portal/"

    /**
     * WebView でのメインフレーム遷移を許可するホスト（完全一致・小文字）。
     *
     * 追加する場合のルール:
     *  1. 実際のログインフローで「そのホストを経由しないと認証が完了しない」ことを確認する
     *     （例: SSO / IdP / 多要素認証ホスト）
     *  2. なぜ必要かをコメントに残す
     *  3. ワイルドカードは使わない。必要なホストだけを列挙する
     */
    val ALLOWED_HOSTS: Set<String> = setOf(
        // PoC の主対象。ポータル本体と /cpsmart/（シラバス等）が同居している。
        "campusplanportal.naramed-u.ac.jp",

        // WebClass (LMS)。
        // 追加理由: 履修時間割の科目リンク（openwin → window.open）が
        //   https://naramed-u.webclass.jp/webclass/tool/naramed-u/singlesignon.php
        //   へ SSO 遷移する。大学が正規に連携している別システムであり、
        //   ポータルの正規導線をたどるために必要（2026-08-28 に実機で遷移先を確認）。
        // 注意: これは CampusPlan とは別システム。ここで扱うのは
        //   「正規リンクをたどって表示する」ところまでで、
        //   WebClass 側の DOM 取得や API 利用は本 PoC の対象外とする。
        "naramed-u.webclass.jp",
    )

    /**
     * サブリソース（画像 / CSS / JS / XHR）まで allowlist で遮断するか。
     *
     * Phase 1 では false。
     * 理由: 正規のログインフローがどの外部ホストを必要とするか未確認の段階で遮断すると、
     *       「ブロックのせいで動かない」のか「実装が悪い」のか切り分けできなくなるため。
     *       まずは [jp.naramed.campusplanpoc.web.PortalWebViewClient] が
     *       allowlist 外ホストへのサブリソース要求を記録するだけにして、
     *       実測に基づいて allowlist を確定させてから true に切り替える。
     *
     * メインフレームの遷移は本フラグに関係なく常に allowlist で制限される。
     */
    const val BLOCK_NON_ALLOWLISTED_SUBRESOURCES: Boolean = false

    /** 許可するスキーム。https のみ（平文 http は許可しない）。 */
    val ALLOWED_SCHEMES: Set<String> = setOf("https")

    /**
     * Phase 3: 画面遷移のショートカット。
     *
     * すべて 2026-08-28 に実機の DOM から採取した実在の href。推測は含まない。
     *
     * 意図的に含めていないもの:
     *   - /portal/External/RedirectLinkCpSmart?linkid=1600/3000100（履修申請）
     *   - /portal/Questionnaire 系（アンケート回答）
     *   いずれもサーバー側の状態を変更する画面のため、誤タップ防止として除外する。
     */
    val SHORTCUTS: List<PortalShortcut> = listOf(
        PortalShortcut(
            label = "履修時間割",
            path = "/portal/TimeTable",
            note = "メニューの onclick が document.location.href = this.href だけなので直接遷移でよい",
        ),
        PortalShortcut(
            label = "シラバス検索",
            path = "/portal/External/RedirectLinkCpSmart?linkid=1900/3000090",
            note = "CampusPlan Smart への外部リダイレクト。遷移先ホストは要確認",
        ),
        PortalShortcut(
            label = "休講・補講",
            path = "/portal/KyukoHokoEtc",
        ),
        PortalShortcut(
            label = "講義連絡",
            path = "/portal/LectureNewsHaishin",
        ),
        PortalShortcut(
            label = "出欠状況",
            path = "/portal/External/RedirectLinkCpSmart?linkid=1800/2001100",
            note = "参照のみ。出欠『登録』ではない",
        ),
    )

    /**
     * Phase 4: 正規フロントエンドが実際に叩いている API。
     *
     * すべて 2026-08-28 に実機の通信を観測して確認したもの。推測した URL は含まない。
     * いずれも GET で、Cookie セッションで認証される（Authorization ヘッダは使われていない）。
     *
     * 読み取り専用のものだけを載せる。
     */
    val API_ENDPOINTS: List<ApiEndpoint> = listOf(
        ApiEndpoint(
            label = "時間割",
            path = "/portal/api/KogiJikanwari/1?nendo=",
            note = "履修時間割ページが読み込み時に叩いている。科目コード kogiCd を含むはず",
        ),
        ApiEndpoint(
            label = "講義カレンダー",
            path = "/portal/api/KogiCalendar/?uKbn=1&start=2026-08-24&end=2026-08-31",
            note = "期間はページ側が動的に組み立てている",
        ),
    )

    /** ショートカットの相対パスを絶対 URL にする */
    fun absoluteUrl(path: String): String = "https://campusplanportal.naramed-u.ac.jp$path"

    /**
     * Phase 5: シラバス参照画面の URL。
     *
     * 2026-08-28 に実機で観測した正規導線と同じ形
     * （シラバス検索の結果から科目を開いたときに、ページ自身が遷移する URL）。
     *
     * この画面を必ず経由する理由:
     *   SystemD Lead の RPC は画面（kinoId）ごとに発行されたトークンを検証する。
     *   シラバス検索など別画面のコンテキストを流用して SyllabusSanshoWebApi を叩くと
     *     {"errorMessages":[{"message":"{0}の値が不正です。","args":["Token"]}]}
     *   が HTTP 400 で返る（2026-08-28 実測）。これは CSRF 保護が正しく効いている状態で、
     *   迂回してはならない。この画面を開いてトークンを正規に発行させること。
     *
     * syllabusKomokuPatternId は実機で 2 が使われていることを確認済み。
     */
    fun syllabusSanshoUrl(
        kogiCd: String,
        nendo: String,
        komokuPatternId: String = "2",
    ): String = absoluteUrl(
        "/cpsmart/gakusei/dashboard/main/ja/simple/1900/3000230/wsl/SyllabusSansho" +
            "?kogiCd=${encode(kogiCd)}" +
            "&kaikoNendo=${encode(nendo)}" +
            "&syllabusKomokuPatternId=${encode(komokuPatternId)}"
    )

    /** クエリ値として安全な形にする。講義コードは DOM 由来なので必ず通す。 */
    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** シラバス参照画面かどうか。遷移完了の判定に使う。 */
    fun isSyllabusSanshoUrl(url: String?): Boolean =
        url != null && url.contains("/wsl/SyllabusSansho", ignoreCase = true)
}
