package jp.naramed.campusplanpoc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import jp.naramed.campusplanpoc.ui.CampusPlanPocTheme
import jp.naramed.campusplanpoc.ui.PortalScreen

/**
 * PoC のエントリポイント。
 *
 * このアプリがやること:
 *   - allowlist で固定した CampusPlan ポータルを WebView で開く
 *   - ユーザー本人がそのページ上で正規ログインする
 *   - ログイン後のページ状態を evaluateJavascript で読み取り、Compose 側へ渡す
 *
 * このアプリがやらないこと（明示的に禁止している事項）:
 *   - ID / パスワードの受け取り・保存・自動入力
 *   - 認証や認可の迂回
 *   - SSL エラーの無視
 *   - allowlist 外ホストの表示
 *   - 本人のセッションで見えない情報へのアクセス
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CampusPlanPocTheme {
                PortalScreen()
            }
        }
    }
}
