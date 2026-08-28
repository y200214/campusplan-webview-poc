package jp.naramed.campusplanpoc.model

import kotlinx.serialization.Serializable

/**
 * 履修時間割。
 *
 * 出どころは DOM の data-cp-kogicd 属性。
 * API（/portal/api/KogiJikanwari）は本人のセッションでも 401 が返るため使えないことを
 * 実測で確認済み（jQuery・fetch・Bearer の 3 方式すべてで 401）。
 */
@Serializable
data class TimeTable(
    val url: String = "",
    val title: String = "",
    /** 例: 2026年度 前期 時間割 */
    val heading: String = "",
    val entries: List<TimeTableEntry> = emptyList(),
    val tables: List<TimeTableGrid> = emptyList(),
) {
    /** 同じ講義が複数コマに出るので、講義コードで一意化した科目一覧 */
    val distinctCourses: List<TimeTableEntry>
        get() = entries.distinctBy { it.kogiCd }.sortedBy { it.kogiCd }
}

@Serializable
data class TimeTableEntry(
    /** 講義コード。シラバス照会のキーになる */
    val kogiCd: String = "",
    /** 講義名 */
    val kogiNm: String = "",
    val tableKey: String = "",
    val rowIndex: Int = -1,
    val cellIndex: Int = -1,
    val cellText: String = "",
)

@Serializable
data class TimeTableGrid(
    val key: String = "",
    /** 表の見出し行。曜日が入るはず */
    val colHeaders: List<String> = emptyList(),
    /** 各行の先頭セル。時限が入るはず */
    val rowHeaders: List<RowHeader> = emptyList(),
)

@Serializable
data class RowHeader(
    val rowIndex: Int = -1,
    val text: String = "",
)
