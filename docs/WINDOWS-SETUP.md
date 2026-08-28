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

### IDE が要らない場合（コマンドラインツールだけ）

実機で動かすだけなら Android Studio は不要。cmdline-tools だけで足りる。

<https://developer.android.com/studio#command-line-tools-only> の Windows 版 zip を
`%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest` に展開する
（zip の中の `cmdline-tools` フォルダを `latest` にリネームして置く）。

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\android.exe" --no-metrics sdk install `
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

新しい cmdline-tools では `sdkmanager` は非推奨になり `android sdk` が後継。
`--licenses` は不要（ライセンスは install 時に処理される）。
`--no-metrics` はグローバルフラグなので **サブコマンドより前** に置くこと。

この場合 `local.properties` は自動生成されないので、自分で作る。

```
sdk.dir=C:/Users/<ユーザー名>/AppData/Local/Android/Sdk
```

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

> ここで `Unable to establish loopback connection` が出て必ず落ちるマシンがある。
> 下の「トラブルシューティング」を見ること。ネットワークの問題ではない。

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

## トラブルシューティング

### Gradle が `Unable to establish loopback connection` で落ちる

2026-08-28、Windows PC（DESKTOP-6O7CO2T / Windows 11 build 26200）で遭遇。
Gradle がタスクを 1 つも実行せず、デーモン接続の時点で必ず落ちる。

```
FAILURE: Build failed with an exception.
* What went wrong:
java.io.IOException: Unable to establish loopback connection
```

**ネットワークやファイアウォールの問題ではない。**
TCP のループバック疎通テストは成功してしまうので切り分けを誤りやすい。

真因は AF_UNIX（Unix ドメインソケット）。JDK の `Selector.open()` は内部で
自己接続パイプを作るが、Windows ではそれを **AF_UNIX ソケットとして `%TEMP%` に置く**。
そのソケットファイルは reparse point として作られる。

このマシンでは **`%USERPROFILE%\AppData` ツリー配下に限って** その reparse point が壊れ、
`connect` が `WSAEINVAL (Invalid argument)` で失敗する。
既定の `%TEMP%` は `%LOCALAPPDATA%\Temp` なので、JVM の NIO を使うツールは軒並み死ぬ。

切り分けに使ったワンライナー（JDK 17 以降。`OK` が出れば健全）:

```powershell
@'
import java.net.*; import java.nio.channels.*; import java.nio.file.*;
public class UdsProbe { public static void main(String[] a) throws Exception {
  Path p = Paths.get(a[0], "probe.sock"); Files.deleteIfExists(p);
  UnixDomainSocketAddress ua = UnixDomainSocketAddress.of(p);
  try (ServerSocketChannel s = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
    s.bind(ua);
    try (SocketChannel c = SocketChannel.open(ua)) { System.out.println("OK   " + a[0]); }
    catch (Exception e) { System.out.println("FAIL " + a[0] + "  " + e.getMessage()); } }
}}
'@ | Set-Content .\UdsProbe.java -Encoding ascii
java .\UdsProbe.java $env:TEMP
java .\UdsProbe.java $HOME
```

**対処:** `TEMP` / `TMP` を `AppData` の外へ逃がしてから Gradle を起動する。

```powershell
$env:TEMP = "$HOME\.gradle\tmp"; $env:TMP = $env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
.\gradlew.bat assembleDebug
```

`-Djava.io.tmpdir` や `-Djdk.nio.channels.unixdomain.tmpdir` では直らない。
implicit bind はネイティブの `GetTempPath()`（＝環境変数 TEMP）を見るため。
Gradle デーモンは起動元の環境変数を継承するので、上のように env を設定すれば足りる。

Android Studio から実行する場合も同じ経路を通るので、
Studio 自体を上記 env を設定したシェルから起動するか、ユーザー環境変数 `TEMP` を変える。

**原因として否定できたもの**（同じ罠を追いかけ直さないための記録）:

| 疑ったもの | 結果 |
| --- | --- |
| ファイアウォール / TCP ループバック | ❌ TCP の bind+connect は成功する |
| IPv6（`java.net.preferIPv4Stack`） | ❌ 変化なし |
| `%TEMP%` が 8.3 短縮名（`Y79E8~1.KAZ`）だから | ❌ 実パスでも同じく失敗 |
| パス長（`sun_path` 108 バイト制限） | ❌ 失敗するパスの方が短い |
| フォルダの ACL（サンドボックス系ツールの書き換え） | ❌ 継承を切って最小 ACL にしても失敗 |
| Hidden 属性 / フォルダ名 | ❌ 別の場所で再現しない |
| Defender の Controlled Folder Access | ❌ 保護対象の Documents では成功する |
| reparse point / junction | ❌ AppData 自体は reparse point ではない |

`AppData` というディレクトリノード固有の現象、というところまでしか切り分けられていない。
minifilter ドライバの確認（`fltmc filters`）には管理者権限が要るので未実施。

**副作用:** 失敗するたびに 0 バイトの壊れた reparse point が `%LOCALAPPDATA%\Temp` に残る。
`socket_<乱数>` という名前で、削除しようとすると
`Error 1920: The file cannot be accessed by the system.` になり消せない。
`fsutil reparsepoint delete` も同じエラー。管理者権限か再起動が要る。

### `adb install` が `INSTALL_FAILED_UPDATE_INCOMPATIBLE` で失敗する

```
Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package
jp.naramed.campusplanpoc.debug signatures do not match newer version; ignoring!]
```

debug ビルドの署名鍵はマシンごとに自動生成される（`~/.android/debug.keystore`）。
Mac mini でビルドしたものが実機に入っている状態で Windows ビルドを入れようとすると衝突する。

対処は 2 つ。

1. **keystore を揃える（推奨）** — 片方の `~/.android/debug.keystore` をもう片方にコピーする。
   以後どちらのマシンからでもアンインストールなしで上書きできる。
   上書き前に元のファイルを退避しておくこと。
   Tailscale が入っているので Taildrop が楽:

   ```bash
   # Mac 側で実行
   tailscale file cp ~/.android/debug.keystore <Windows のマシン名>:
   ```

   ```powershell
   # Windows 側で受け取る
   tailscale file get $HOME\.android
   ```

2. **アンインストールして入れ直す** — `adb uninstall jp.naramed.campusplanpoc.debug`。
   アプリのデータが消えるので、**WebView の Cookie も消えて CampusPlan に再ログインが要る**。

---

## 続きから再開するには

未解決の課題は README の「未解決」節にある。

### 動作確認のしかた（シラバス取得）

手順を覚えておく必要はない。計装の有効化も画面遷移もアプリ側でやる。

1. 「履修時間割」→ 調査ツール →「時間割を表示（DOMから取得）」
2. 調査ツールを閉じると履修科目の一覧が出る
3. 科目をタップする

期待する結果:

| タップした科目 | 出るもの |
| --- | --- |
| `G001136` (主)医療教育学講義 | `(主)医療教育学講義 / 本文 13140 文字` とシラバス HTML |
| `G000004` 病理学 | `シラバス未登録（G000004 はシラバスが登録されていません）` |

ログで追う場合:

```powershell
adb logcat -s SyllabusFlow PageFetcher
```

```
SyllabusFlow: シラバス参照画面へ遷移: kogiCd=G001136 nendo=2026
SyllabusFlow: シラバス参照画面に到達。取得へ進む: kogiCd=G001136
PageFetcher: シラバス: kogiCd=G001136 名称=(主)医療教育学講義 init=200 guid=取得 errorMsg=- 未登録=false 本文取得=true
```

`ctx=SyllabusSansho` になっていることを確認すること。
`ctx=SyllabusKensaku(流用)` だと、その画面のトークンでは弾かれる
（`{"args":["Token"]}` / HTTP 400）。参照画面への遷移が効いていない。

### 詰まったときに見るところ

| 症状 | 原因 |
| --- | --- |
| `認証コンテキスト未取得` | 計装が入っていない。`window.__pocCtx` が空 |
| `{"args":["Token"]}` HTTP 400 | 参照画面を経由せず別画面のコンテキストで叩いている |
| `errorMsg: MSG5` | 異常ではない。その講義コードにシラバスが登録されていない |
