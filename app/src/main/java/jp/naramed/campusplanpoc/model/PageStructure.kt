package jp.naramed.campusplanpoc.model

import kotlinx.serialization.Serializable

/**
 * assets/js/page_structure.js が返すページ構造。
 *
 * 個人情報の方針:
 *   form の入力「値」は JS 側で取得していない（name / type のみ）。
 *   このモデルにも value を持たせないこと。
 */
@Serializable
data class PageStructure(
    val structureVersion: Int = 0,
    val url: String = "",
    val path: String = "",
    val title: String = "",
    val readyState: String = "",
    /** 通常の href リンク（同一オリジンのみ） */
    val navLinks: List<PageLink> = emptyList(),
    /** JavaScript で遷移するリンク。ポータル系のメニューは大半がこちら */
    val scriptLinks: List<PageLink> = emptyList(),
    val headings: List<PageHeading> = emptyList(),
    val forms: List<PageForm> = emptyList(),
    val buttons: List<PageButton> = emptyList(),
    /** onclick から参照されているグローバル関数のソース（実行はしていない） */
    val handlerSources: List<HandlerSource> = emptyList(),
    /** API 認証方式の調査結果。トークンの値は含まない */
    val ajax: AjaxInfo? = null,
    /** 上限に達して打ち切られたか */
    val truncated: Boolean = false,
) {
    val totalLinks: Int get() = navLinks.size + scriptLinks.size
}

@Serializable
data class PageLink(
    val text: String = "",
    /** 解決済み絶対 URL */
    val href: String = "",
    /** DOM 上の生の href 属性（javascript: などを判別するため） */
    val rawHref: String = "",
    val id: String = "",
    val cls: String = "",
    val hasOnclick: Boolean = false,
    val onclick: String = "",
    val data: Map<String, String> = emptyMap(),
) {
    /**
     * onclick 付きリンクのうち、「href へ遷移するだけ」と確認できるものかどうか。
     *
     * CampusPlan のメニューは
     *   onclick="document.location.href = this.href; return false;"
     * という形で、href に本来の遷移先を持っている。
     * この形に限っては onclick を実行せず href へ直接遷移して差し支えない。
     *
     * 逆に window.open / submit() / localStorage 操作などを含む onclick は
     * 副作用があるため対象外にする（勝手に実行しない）。
     */
    val isPlainNavigation: Boolean
        get() {
            if (!rawHref.startsWith("/")) return false
            val oc = onclick.replace(" ", "")
            return oc.isEmpty() || oc.contains("document.location.href=this.href")
        }
}

/**
 * ページが API を呼ぶときの仕組み。
 * 値（トークン本体）は意図的に持たない。キー名と関数のソースのみ。
 */
@Serializable
data class AjaxInfo(
    val hasJQuery: Boolean = false,
    val ajaxSetupHeaderNames: List<String> = emptyList(),
    val beforeSendSource: String = "",
    val localStorageKeys: List<String> = emptyList(),
    val sessionStorageKeys: List<String> = emptyList(),
)

@Serializable
data class HandlerSource(
    val name: String = "",
    val source: String = "",
)

@Serializable
data class PageHeading(
    val tag: String = "",
    val text: String = "",
)

@Serializable
data class PageForm(
    val name: String = "",
    val id: String = "",
    val action: String = "",
    val method: String = "",
    val fieldCount: Int = 0,
    val fields: List<PageField> = emptyList(),
)

/** value は意図的に持たない */
@Serializable
data class PageField(
    val name: String = "",
    val id: String = "",
    val type: String = "",
)

@Serializable
data class PageButton(
    val text: String = "",
    val id: String = "",
    val name: String = "",
    val onclick: String = "",
    val data: Map<String, String> = emptyMap(),
)
