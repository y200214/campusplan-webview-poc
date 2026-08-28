package jp.naramed.campusplanpoc.model

/**
 * Phase 5: シラバス本文を独自 UI で出すための構造化データ。
 *
 * 出どころは webmvc/SyllabusSansho が返す HTML（印刷用の静的ページ）。
 * 生 HTML をそのまま画面に流さず、ここで (ラベル, 本文) の並びに落とす。
 */
data class SyllabusDetail(
    /** 講義名称。ヘッダに出す */
    val title: String = "",
    /** 講義コード */
    val kogiCd: String = "",
    /** 表示順のセクション。ラベルはポータルの th をそのまま使う */
    val sections: List<Section> = emptyList(),
) {
    data class Section(val label: String, val text: String)
}

/**
 * webmvc/SyllabusSansho の HTML から [SyllabusDetail] を作る。
 *
 * 実測した構造（2026-08-28、G001136 で採取）:
 *  - フィールドごとに `<tr><th>ラベル</th><td>…<div class="data">値</div>…</td></tr>`
 *  - 長文セクション（概要・評価方法・授業計画など）は th class="wrap"
 *  - モバイル用/デスクトップ用に同じ内容の表が重複している
 *    （is-hidden-mobile / is-hidden-tablet）→ ラベルで一意化する
 *
 * 方針:
 *  - DOM パーサは使わず、<th> を区切りに前から舐める。
 *    td の中に入れ子の表が来ても壊れないようにするため
 *    （tr 単位の正規表現は入れ子で破綻する）。
 *  - 値が空のフィールド（配当年など）は出さない。
 */
object SyllabusHtml {

    private val TH = Regex("<th[^>]*>", RegexOption.IGNORE_CASE)
    private val TH_CLOSE = Regex("</th>", RegexOption.IGNORE_CASE)
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_CLOSE = Regex("</(div|p|tr|td|li|table)>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")

    fun parse(html: String): SyllabusDetail {
        // ラベル → 本文。重複表があるので、最初に値が入ったものを採る
        val sections = LinkedHashMap<String, String>()

        val parts = TH.split(html)
        // parts[0] は最初の <th> より前（ヘッダ等）なので捨てる
        for (part in parts.drop(1)) {
            val close = TH_CLOSE.find(part) ?: continue
            val label = toText(part.substring(0, close.range.first))
                .replace("\n", " ").trim()
            if (label.isEmpty()) continue

            // 次の <th> までの残り全部がこのフィールドの値。
            // 行の終端タグを厳密に探さないのは、td 内に入れ子構造が来ても
            // 値を取りこぼさないようにするため。表間の空白は toText が落とす。
            val value = toText(part.substring(close.range.last + 1))

            val existing = sections[label]
            if (existing.isNullOrEmpty()) sections[label] = value
        }

        return SyllabusDetail(
            title = sections["講義名称"].orEmpty(),
            kogiCd = sections["講義コード"].orEmpty(),
            sections = sections
                .filterValues { it.isNotEmpty() }
                .map { (label, text) -> SyllabusDetail.Section(label, text) },
        )
    }

    /** タグを落として読めるテキストにする。段落は改行として残す。 */
    private fun toText(fragment: String): String {
        val withBreaks = fragment
            .replace(BR, "\n")
            .replace(BLOCK_CLOSE, "\n")
        val plain = TAG.replace(withBreaks, "")
        val decoded = decodeEntities(plain)
        // 行ごとに整えて空行を潰す。全角空白だけの行も空とみなす
        return decoded.lines()
            .map { it.trim().trim('　') }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
