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
