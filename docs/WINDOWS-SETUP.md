# Windows で開発を再開する手順

Mac mini で作った PoC を Windows PC に持ってきて、続きから作業するための手順。
所要時間は 40〜70 分（ほとんどはダウンロード待ち）。

---

## 1. Android Studio を入れる

<https://developer.android.com/studio> から Windows 版をダウンロードしてインストール。

初回起動のウィザードは:

| 画面 | 選ぶもの |
| --- | --- |
| Import Settings | Do not import settings |
| Install Type | **Standard** |
| License Agreement | 内容を確認して **Accept** → Finish |

Android SDK が数 GB ダウンロードされる。終わるまで待つ。

---

## 2. JDK を確認する（重要）

このプロジェクトの Gradle は 8.9 で、**Java 22 以降では動かない**。
Android Studio 2026 の同梱 JDK は Java 25 なので、そのままだと Gradle Sync が失敗する。

対処は Android Studio の設定で JDK を切り替えるだけ。

```
File > Settings > Build, Execution, Deployment > Build Tools > Gradle
  → Gradle JDK: 17 〜 21 のものを選ぶ
```

一覧に無ければ、同じドロップダウンの `Download JDK...` から **JDK 21** を落とす。

> Mac 側では `%USERPROFILE%\.gradle\gradle.properties` に相当するユーザー設定で
> `org.gradle.java.home` を指定して回避している。
> Windows でも同じ方法を取ってよいが、Studio の設定で選ぶほうが簡単。

---

## 3. GitHub CLI を入れて認証する

```powershell
winget install --id GitHub.cli
```

インストール後、**PowerShell を開き直してから**:

```powershell
gh auth login
```

| 質問 | 答え |
| --- | --- |
| What account do you want to log into? | GitHub.com |
| What is your preferred protocol? | HTTPS |
| Authenticate Git with your GitHub credentials? | **Yes** |
| How would you like to authenticate? | Login with a web browser |

---

## 4. リポジトリを clone

```powershell
cd $HOME\Documents
git clone https://github.com/y200214/campusplan-webview-poc.git
```

Private リポジトリだが、手順 3 の認証が済んでいれば普通に落ちてくる。

**`gradle.properties` の編集は不要。**マシン固有の設定は入れていない。

---

## 5. Android Studio でプロジェクトを開く

`File > Open` → clone したフォルダを選択 → Gradle Sync を待つ。

`local.properties`（SDK のパス）は Studio が自動生成する。

コマンドラインからビルドする場合:

```powershell
.\gradlew.bat assembleDebug
```

---

## 6. Pixel を接続する

Pixel は USB で Windows PC に直結する。

1. Pixel: 設定 → デバイス情報 → **ビルド番号を 7 回タップ**
2. 設定 → システム → 開発者向けオプション → **USB デバッグ ON**
3. USB 接続 → Pixel に出る「USB デバッグを許可しますか」を **許可**

確認:

```powershell
adb devices
```

`adb` が見つからない場合は、SDK の platform-tools に PATH を通す。

```
%LOCALAPPDATA%\Android\Sdk\platform-tools
```

---

## 7. 動作確認

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n jp.naramed.campusplanpoc.debug/jp.naramed.campusplanpoc.MainActivity
```

CampusPlan のログイン画面が出れば成功。ログインは WebView 内で本人が行う。

ログを見る:

```powershell
adb logcat -s PortalWebViewClient PageProbe PageStructure TimeTable PageFetcher NetObserver PortalNetwork PortalBridge
```

---

## 続きから再開するには

未解決の課題は README の「未解決」節にある。次にやることは 1 つ。

**`I243010` でシラバス取得が通るか確認する**

1. 「シラバス検索」→ 調査ツール →「本文記録」ON
2. 検索を実行 → 結果の科目をタップしてシラバス詳細を開く
3. そのページのまま →「シラバス取得テスト (I243010)」をタップ

| 結果 | 意味 |
| --- | --- |
| `guid` と `sanshoUrl` が返る | 実装は正しい。時間割の G 系科目にシラバスが無いだけ |
| `errorMsg: MSG5` | `kaikoNendo` か `syllabusKomokuPatternId` が違う。記録から正しい値を拾って合わせる |

前者なら、次は `sanshoUrl` を GET してシラバス本文を取得し、Compose 側で一覧表示する。
