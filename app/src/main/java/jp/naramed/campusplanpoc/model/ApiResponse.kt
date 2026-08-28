package jp.naramed.campusplanpoc.model

import kotlinx.serialization.Serializable

/** ページコンテキストの fetch が返した結果 */
@Serializable
data class ApiResponse(
    val id: String = "",
    val ok: Boolean = false,
    /** Authorization ヘッダを付けられたか。トークンの値は保持しない */
    val tokenPresent: Boolean = false,
    /** トークンの形状だけの診断情報。値そのものは含まない */
    val tokenInfo: TokenInfo? = null,
    val status: Int = 0,
    val contentType: String = "",
    val truncated: Boolean = false,
    val body: String = "",
    val error: String? = null,
    /** どの画面の認証コンテキストを使ったか（デバッグ用） */
    val contextSource: String = "",
    /** Phase 5: シラバス取得のときだけ入る。本文以外の判定材料。 */
    val syllabus: SyllabusResult? = null,
)

/**
 * シラバス取得の結果。
 *
 * 取得は 2 段階で、この型はその両方の結末を表す。
 *   1. SyllabusSanshoWebApi/initFindAndUpdate → guid を得る
 *   2. /cpsmart/gakusei/wsl/webmvc/SyllabusSansho?TYPE=0&GUID=<guid> → 本文
 *
 * 注意: ステージ 1 の応答に含まれる sanshoUrl は、**成功時でも null** で返る
 * （2026-08-28 実測）。本文の URL は sanshoUrl ではなく guid から組み立てること。
 */
@Serializable
data class SyllabusResult(
    val kogiCd: String = "",
    /** サーバーが返した講義名。未登録のときは空 */
    val kogiNm: String = "",
    val guid: String = "",
    /**
     * サーバーが返したエラーコード。
     * MSG5 は「そのコードのシラバスが登録されていない」ことを指す。
     */
    val errorMsg: String? = null,
    /**
     * シラバスが登録されていない。
     *
     * 通信もトークンも正常で、単にデータが無い状態。
     * 実測では大学院の共通科目（G0000xx）が該当し、
     * 専攻科目（G001xxx / G002xxx）や学部科目（I24xxxx）は登録されている。
     * 失敗として扱わず、UI では「シラバス未登録」と出すこと。
     */
    val notRegistered: Boolean = false,
    /** ステージ 1 の HTTP ステータス */
    val initStatus: Int = 0,
    /** ステージ 2 まで到達して本文を取得できたか */
    val bodyFetched: Boolean = false,
)

/**
 * トークンの形状だけを表す診断情報。
 * 値・JWT のクレーム（氏名や ID）は意図的に持たない。exp のみ。
 */
@Serializable
data class TokenInfo(
    val present: Boolean = false,
    val length: Int = 0,
    val jsonQuoted: Boolean = false,
    val looksJwt: Boolean = false,
    val expEpoch: Long = 0,
    val expired: Boolean? = null,
)

/** 叩ける API の定義。すべて実測で存在を確認したものだけを並べる。 */
data class ApiEndpoint(
    val label: String,
    val path: String,
    val note: String = "",
)
