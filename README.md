# CampusPlan PoC

既存 Web ポータル（CampusPlan / 奈良県立医科大学）を、**正規のログインセッションのまま**
Android アプリから扱うための技術検証。

最終目標は病院内の研修医評価ポータルへの応用だが、まず自分が正規に利用権限を持つ
CampusPlan で「認証を維持したまま画面遷移を短縮できるか」を検証している。

---

## 大原則

やること:

- allowlist で固定したホストのみを WebView で表示する
- ユーザー本人がその WebView 内で **正規ログイン** する
- ログイン済みセッション（Cookie / トークン）を **ページの中に置いたまま** 利用する
- 取得した情報を Compose の専用 UI として表示する

やらないこと（設計として禁止）:

- 資格情報のハードコード・受け取り・保存
- 認証 / 認可 / CSRF 保護の迂回
- SSL エラーの無視（`SslErrorHandler.proceed()` は 1 箇所も存在しない）
- allowlist 外ホストの表示
- `addJavascriptInterface` の使用（代わりに WebMessageListener をオリジン限定で使用）
- 既存システムへの登録処理（読み取りのみ）

---

## 進捗（2026-08-28 時点）

| Phase | 内容 | 状態 |
| --- | --- | --- |
| 1 | 安全な WebView 基盤・ログイン・セッション維持 | ✅ 実機検証済み |
| 2 | DOM 取得（構造・リンク・form・ハンドラのソース） | ✅ 完了 |
| 3 | 画面遷移の短縮（href ベースのショートカット） | ✅ 完了 |
| 4 | API 調査（通信観測・POST ボディ観測） | ✅ 経路確立 |
| 5 | Compose 専用 UI | 🔄 時間割の科目一覧まで |

### 実機で確認したポータルの構造

- ポータル本体は **ASP.NET MVC**。`__RequestVerificationToken` を使用
- `/portal/api/*` に JSON API があるが、**`KogiJikanwari` は本人セッションでも 401**
  （jQuery / fetch / Bearer の 3 方式すべてで 401 になることを実測済み）
  → 時間割は **DOM の `data-cp-kogicd`** から取得する方式を採用
- シラバスは `/cpsmart/` 配下の **SystemD Lead フレームワーク**（SPA）
  - RPC 形式: `POST .../WebRoot/<Namespace>.App.<Name>WebApi/<method>`
  - 認証は `tempData.entryContext.token`。**画面（kinoId）ごとに発行される**
  - シラバス参照画面を開かずに呼ぶと `{"message":"{0}の値が不正です。","args":["Token"]}`
    → CSRF 保護が効いている。迂回せず、正規手順で画面を開いてから使う
- シラバス取得は 2 段階
  1. `SyllabusSanshoWebApi/initFindAndUpdate` に `kogiCd` / `kaikoNendo` → `{guid, sanshoUrl}`
  2. `GET /cpsmart/gakusei/wsl/webmvc/SyllabusSansho?TYPE=0&GUID=<guid>` → 本文
- 時間割の科目リンクは `openwin()` → `window.open("/portal/External/WebClass?kogi=...")`
  → **WebClass (naramed-u.webclass.jp)** へ SSO 遷移。allowlist に追加済み

### 未解決

- 時間割の科目（`G` 始まりのコード）で `initFindAndUpdate` を呼ぶと `errorMsg: "MSG5"`
  （HTTP 200・全フィールド null）。シラバス DB に該当が無い可能性が高い。
  実在が確認できている `I243010` での切り分けが未実施。

---

## ビルド

### 必要なもの

| 項目 | 値 |
| --- | --- |
| AGP | 8.7.3 |
| Gradle | 8.9 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |
| JDK | **17〜21**（22 以降は Gradle 8.9 が非対応） |

### ⚠ JDK について

Android Studio 2026 の同梱 JDK は **Java 25** で、Gradle 8.9 は非対応。
そのままだと Gradle Sync が `Could not determine java version` 等で失敗する。

対処は 2 通り。**プロジェクトの `gradle.properties` には書かないこと**（別マシンで壊れる）。

- Android Studio の
  `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`
  で JDK 17〜21 を選ぶ（推奨・簡単）
- またはユーザー単位の Gradle 設定に書く
  - macOS / Linux: `~/.gradle/gradle.properties`
  - Windows: `%USERPROFILE%\.gradle\gradle.properties`

  ```properties
  org.gradle.java.home=<JDK 17〜21 のパス>
  ```

Mac mini では後者を採用している（`brew install openjdk@21` で入れた JDK を指定）。

### 手順

1. Android Studio でこのディレクトリを Open
2. Gradle Sync（`local.properties` は Studio が自動生成する）
3. 実機または API 26+ のエミュレータで Run

**Windows で新しく環境を作る場合は [docs/WINDOWS-SETUP.md](docs/WINDOWS-SETUP.md) に手順をまとめてある。**

コマンドラインからビルドする場合:

```bash
./gradlew assembleDebug
```

---

## 画面の使い方

- 上部チップ … ログイン状態の推定
- URL 行 … **クエリは伏せて表示**（セッション ID が URL に載る場合の漏洩対策）
- 紫のボタン … Phase 3 のショートカット（履修時間割 / シラバス検索 / 休講補講 / 講義連絡 / 出欠状況）
- `▼ 調査ツール` … 既定は閉じている。開くと以下が出る
  - **時間割を表示** … DOM から履修科目を取得。科目タップでシラバス取得
  - **API: 〜** … 観測済みエンドポイントを叩く
  - **401原因の比較テスト** … jQuery / fetch / Bearer の 3 方式を逐次比較
  - **本文記録 ON/OFF** … POST ボディの観測（後述）
  - **構造を取得** … ページの DOM 構造を一覧表示
  - **通信ログ** … 観測したリクエスト一覧

### シラバス取得の手順（重要）

トークンをどこにも保存しない方針のため、**取得はシラバス参照画面の上で行う**。

1. 「履修時間割」→「時間割を表示」で科目一覧を取得
2. 「シラバス検索」へ移動 → **「本文記録」を ON**
3. 検索を実行 → **結果の科目をタップしてシラバス詳細を開く**
   （ここで参照画面の正規トークンが発行される）
4. **そのページのまま**「時間割を表示」→ 科目名をタップ

ページを移動するとトークンは失われる。これは制約ではなく、
「アプリが資格情報を保持しない」ことを優先した結果の意図的な設計。

---

## 通信観測について

`本文記録` は **ページの `XMLHttpRequest.send` と `fetch` を包む計装**で、
これまでの読み取り専用プローブと違い **ページの JS を書き換える**。

- 元の関数をそのまま呼び、内容は改変しない
- 追加のリクエストを発行しない
- 記録前に `token` / `userId` / `userName` / `gakusekiNo` / `kojinId` などを `<redacted>` に置換
- debug ビルドのみ

Kotlin 側でログへ出す応答も `PageFetcher.redactJson()` を通している。

---

## ログ

`adb logcat` のタグ:

| タグ | 内容 |
| --- | --- |
| `PortalWebViewClient` | 遷移許可 / ブロック、SSL エラー、HTTP エラー |
| `PortalNetwork` | 観測したリクエスト（URL・メソッド・代表ヘッダ） |
| `PageProbe` / `PageStructure` | ページ構造 |
| `TimeTable` | 取得した時間割 |
| `PageFetcher` | API 取得結果（秘匿処理済み） |
| `NetObserver` | POST ボディ観測（秘匿処理済み） |
| `PortalBridge` | WebMessageListener の状態 |

URL は `scheme://host/path` までしか出力しない（`UrlPolicy.redactForLog`）。

---

## ファイル構成

```
app/src/main/
├── AndroidManifest.xml
├── assets/js/
│   ├── page_probe.js            # 軽量プローブ（ページ読み込みごと）
│   ├── page_structure.js        # 構造取得（ボタン押下時）
│   ├── timetable.js             # 履修時間割の抽出
│   ├── fetch_api.js             # GET API 呼び出し
│   ├── api_compare.js           # 401 切り分け用の 3 方式比較
│   ├── syllabus_fetch.js        # シラバス取得
│   ├── net_observer_install.js  # POST ボディ観測の計装
│   └── net_observer_read.js     # 同・読み出し
├── java/jp/naramed/campusplanpoc/
│   ├── MainActivity.kt
│   ├── PortalConfig.kt          # allowlist / ショートカット / API 定義
│   ├── model/
│   ├── security/UrlPolicy.kt    # 許可判定とログ秘匿
│   ├── ui/                      # Compose 画面と ViewModel
│   └── web/                     # WebView 設定・各 Client・Bridge・Fetcher
└── res/xml/network_security_config.xml
```

---

## 将来（病院版）

同じ考え方を研修医評価ポータルへ適用する。既存システムの
authentication / authorization / audit log / validation / CSRF protection は**すべて維持**する。
目的は「認証をなくす」ことではなく「**認証後の無駄な画面遷移をなくす**」こと。

最終段階では Android Dedicated Device / Lock Task Mode によるキオスク化を検討するが、
**Device Owner の設定は専用端末でのみ行う**（個人端末では絶対に実施しない）。
