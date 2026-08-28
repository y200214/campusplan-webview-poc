package jp.naramed.campusplanpoc.model

import kotlinx.serialization.Serializable

/**
 * assets/js/page_probe.js が返す JSON の Kotlin 表現。
 *
 * Phase 1 では「構造」だけを持ち、ページ本文などの個人情報は保持しない。
 * Phase 2 でメニュー / リンク / form action を追加する際も、
 * 「必要なフィールドだけ増やす」方針を守ること。
 */
@Serializable
data class PageSnapshot(
    val probeVersion: Int = 0,
    val url: String = "",
    val origin: String = "",
    val path: String = "",
    val title: String = "",
    val readyState: String = "",
    val passwordFieldCount: Int = 0,
    val formCount: Int = 0,
    val frameCount: Int = 0,
    val textLength: Int = 0,
    val linkCount: Int = 0,
    val error: String? = null,
)
