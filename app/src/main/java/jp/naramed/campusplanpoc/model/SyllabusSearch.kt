package jp.naramed.campusplanpoc.model

import kotlinx.serialization.Serializable

/**
 * Phase 5: シラバス検索の結果。
 *
 * 検索リクエストはページ自身に組み立てさせ、こちらは応答だけ受け取る
 * （js/syllabus_search.js のコメント参照）。
 */
@Serializable
data class SyllabusSearchResult(
    val id: String = "",
    val ok: Boolean = false,
    val rows: List<Row> = emptyList(),
    /** 表示上限で打ち切ったか */
    val truncated: Boolean = false,
    /** 打ち切り前の総件数 */
    val total: Int = 0,
    val error: String? = null,
) {
    @Serializable
    data class Row(
        val kogiCd: String = "",
        val kogiNm: String = "",
        /** 開講時期 */
        val kaikojiki: String = "",
        /** 代表教員 */
        val kyoin: String = "",
        /** 対象年次 */
        val nenji: String = "",
    )
}

/**
 * 検索語の正規化。全角の英数字を半角に直す。
 *
 * 日本語 IME で講義コードを打つと「Ｇ００２０」のように全角で確定されることが多く、
 * そのまま送ると 0 件になる（実機で確認）。利用者が悪いのではなく入力環境の都合なので、
 * アプリ側で吸収する。
 *
 * 変換するのは全角英数と全角スペースだけ。かな・漢字・記号には触らないので、
 * 講義名称の検索語を壊さない。
 */
fun normalizeSearchTerm(input: String): String = buildString {
    for (ch in input.trim()) {
        val c = when (ch) {
            in '０'..'９', in 'Ａ'..'Ｚ', in 'ａ'..'ｚ' -> ch - 0xFEE0
            '　' -> ' '
            else -> ch
        }
        append(c)
    }
}

/** 検索画面の状態 */
data class SearchUiState(
    val kogiCd: String = "",
    val kogiNm: String = "",
    val running: Boolean = false,
    /** 一度でも検索したか（未検索と 0 件を区別するため） */
    val searched: Boolean = false,
    val result: SyllabusSearchResult? = null,
)
