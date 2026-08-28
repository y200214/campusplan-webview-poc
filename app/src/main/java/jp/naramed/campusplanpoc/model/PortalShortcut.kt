package jp.naramed.campusplanpoc.model

/**
 * 画面遷移のショートカット。
 *
 * 設計方針（重要）:
 *  - 遷移先は **実物の DOM から確認した href** を使う。CSS セレクタや nth-child には依存しない。
 *    見た目のレイアウトが変わっても壊れないようにするため。
 *  - **読み取り専用の画面だけ**を対象にする。
 *    履修申請・アンケート回答など、サーバー側の状態を変更する画面はショートカットにしない。
 *    誤タップで登録処理が走る事故を構造的に防ぐため。
 */
data class PortalShortcut(
    val label: String,
    val path: String,
    /** この画面が読み取り専用であることを確認済みか */
    val readOnly: Boolean = true,
    val note: String = "",
)
