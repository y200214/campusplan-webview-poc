package jp.naramed.campusplanpoc.model

/**
 * Phase 5: 履修時間割の全科目についてシラバスをまとめて取得した結果。
 *
 * 「裏で時間割とシラバスを開き、UI では時間割の各科目のシラバスを一覧表示する」
 * という当初の目標にあたる。取得は科目ごとに参照画面へ遷移してトークンを
 * 発行させる必要があるため逐次で進む。1 件ずつ状態が更新される。
 */
data class SyllabusDigest(
    val items: List<Item> = emptyList(),
    /** 取得中か */
    val running: Boolean = false,
    /** 何件目まで着手したか（1 始まり。進捗表示用） */
    val doneCount: Int = 0,
    /** 対象の総数 */
    val total: Int = 0,
) {
    val registeredCount: Int get() = items.count { it.status == Status.REGISTERED }

    data class Item(
        val kogiCd: String,
        val kogiNm: String,
        val status: Status,
        /** 登録ありのときだけ入る、構造化したシラバス */
        val detail: SyllabusDetail? = null,
    )

    enum class Status {
        /** まだ取得していない・取得中 */
        PENDING,
        /** シラバス本文まで取得できた */
        REGISTERED,
        /** 通信は正常だがシラバスが登録されていない（MSG5） */
        NOT_REGISTERED,
        /** 取得に失敗した（トークン・通信エラー等） */
        ERROR,
    }
}
