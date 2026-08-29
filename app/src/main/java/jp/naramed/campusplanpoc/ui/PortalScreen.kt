package jp.naramed.campusplanpoc.ui

import android.util.Log
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import jp.naramed.campusplanpoc.auth.CredentialStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.naramed.campusplanpoc.BuildConfig
import jp.naramed.campusplanpoc.PortalConfig
import jp.naramed.campusplanpoc.model.ApiResponse
import jp.naramed.campusplanpoc.model.NetworkObservation
import jp.naramed.campusplanpoc.model.PageStructure
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import jp.naramed.campusplanpoc.nfc.FelicaReader
import jp.naramed.campusplanpoc.nfc.NfcAvailability
import jp.naramed.campusplanpoc.nfc.NfcReaderEffect
import jp.naramed.campusplanpoc.nfc.nfcAvailability
import jp.naramed.campusplanpoc.model.PortalShortcut
import jp.naramed.campusplanpoc.model.SearchUiState
import jp.naramed.campusplanpoc.model.SyllabusSearchResult
import jp.naramed.campusplanpoc.model.SyllabusDetail
import jp.naramed.campusplanpoc.model.SyllabusDigest
import jp.naramed.campusplanpoc.model.SyllabusHtml
import jp.naramed.campusplanpoc.model.TimeTable
import jp.naramed.campusplanpoc.model.PortalEvent
import jp.naramed.campusplanpoc.model.SessionState
import jp.naramed.campusplanpoc.security.UrlPolicy
import jp.naramed.campusplanpoc.web.PageFetcher
import jp.naramed.campusplanpoc.web.PageProbe
import jp.naramed.campusplanpoc.web.PortalBridge
import jp.naramed.campusplanpoc.web.SyllabusFlow
import jp.naramed.campusplanpoc.web.WebViewSecurity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalScreen(viewModel: PortalViewModel = viewModel()) {
    val context = LocalContext.current
    val probe = remember { PageProbe(context.assets) }
    val fetcher = remember { PageFetcher(context.assets) }
    // ページ→Kotlin の応答は fetcher が id で突き合わせる
    val bridge = remember { PortalBridge { message -> fetcher.onBridgeMessage(message) } }
    // シラバスは「参照画面へ遷移してから叩く」。トークンが画面ごとに発行されるため。
    val syllabusFlow = remember { SyllabusFlow(fetcher) }
    val webView: WebView = rememberPortalWebView(viewModel, probe, bridge, syllabusFlow)
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 調査ツールの開閉。既定は閉じる（画面を WebView に譲るため）
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    // 時間割の抽出が終わったら、そのままシラバス一覧まで進むかどうか
    var pendingDigest by remember { mutableStateOf(false) }
    // ログアウトの確認ダイアログ
    var showLogoutConfirm by remember { mutableStateOf(false) }

    /**
     * シラバス取得の入口。ここを通さずに fetcher を直接呼ばないこと。
     *
     * やっていること:
     *  1. 計装（本文記録）を有効にする。
     *     リクエストに載せる entryContext は計装が拾って window.__pocCtx に置いている。
     *     無効なままだと「認証コンテキスト未取得」で必ず失敗する。
     *  2. シラバス参照画面へ遷移してから API を叩く（トークンが画面ごとに発行されるため）。
     */
    val openSyllabus: (String, String) -> Unit = { kogiCd, label ->
        viewModel.onApiRequestStarted("シラバス $label")
        if (!viewModel.state.value.netObserverEnabled) {
            viewModel.setNetObserverEnabled(true)
            // 遷移後のページには onPageStarted の再導入で入る。ここは現ページ用。
            probe.installNetObserver(webView) { }
        }
        syllabusFlow.open(webView, kogiCd, "2026") { res -> viewModel.onApiResponse(res) }
    }

    /**
     * シラバス検索の画面を開く。
     *
     * 検索の実行にはページ側のフォームが必要なので、WebView を検索ページへ送っておく。
     * ユーザーにはネイティブの検索画面だけを見せる（ポータルは覆ったまま）。
     */
    val openSearch: () -> Unit = {
        viewModel.navigate(PortalViewModel.AppScreen.SYLLABUS_SEARCH)
        if (!PortalConfig.isSyllabusKensakuUrl(webView.url)) {
            webView.loadAllowedUrl(
                PortalConfig.absoluteUrl("/portal/External/RedirectLinkCpSmart?linkid=1900/3000090")
            )
        }
    }

    /** 入力された条件で検索する。ページのフォームを動かして応答だけ受け取る。 */
    val runSearch: () -> Unit = {
        val s = viewModel.state.value.search
        if (!PortalConfig.isSyllabusKensakuUrl(webView.url)) {
            viewModel.onSearchResult(
                jp.naramed.campusplanpoc.model.SyllabusSearchResult(
                    ok = false,
                    error = "検索ページの準備がまだです。少し待ってからもう一度お試しください",
                )
            )
        } else {
            viewModel.onSearchStarted()
            // IME が全角で確定しがちなので、送る前に半角へ寄せる
            fetcher.searchSyllabus(
                webView,
                jp.naramed.campusplanpoc.model.normalizeSearchTerm(s.kogiCd),
                jp.naramed.campusplanpoc.model.normalizeSearchTerm(s.kogiNm),
            ) { res -> viewModel.onSearchResult(res) }
        }
    }

    /**
     * 履修時間割を開く。
     *
     * WebView を時間割ページへ飛ばし、抽出が終わるまでネイティブのローディングを出す。
     * この間ポータルは見せない（抽出は onPageFinished 側で自動的に走る）。
     *
     * @param thenDigest true なら抽出後にそのまま全科目のシラバス取得へ進む
     */
    val openTimeTable: (Boolean) -> Unit = { thenDigest ->
        viewModel.navigate(
            if (thenDigest) PortalViewModel.AppScreen.SYLLABUS_LIST
            else PortalViewModel.AppScreen.TIMETABLE
        )
        viewModel.setTimeTableLoading(true)
        pendingDigest = thenDigest
        webView.loadAllowedUrl(PortalConfig.absoluteUrl("/portal/TimeTable"))
    }

    /**
     * 履修時間割の全科目について、シラバスを裏で 1 件ずつ取得して一覧にする。
     *
     * トークンは科目ごとに参照画面へ遷移して発行させる必要があるため逐次で進む。
     * 1 件終わるごとに次を開始し、状態を更新する。当初の目標そのもの。
     */
    val runSyllabusDigest: (List<jp.naramed.campusplanpoc.model.TimeTableEntry>) -> Unit = { courses ->
        // 計装が entryContext を拾う。無効だと必ず「認証コンテキスト未取得」になる。
        if (!viewModel.state.value.netObserverEnabled) {
            viewModel.setNetObserverEnabled(true)
            probe.installNetObserver(webView) { }
        }
        viewModel.startSyllabusDigest(courses.map { it.kogiCd to it.kogiNm })

        val nendo = "2026"

        fun toItem(course: jp.naramed.campusplanpoc.model.TimeTableEntry, res: ApiResponse):
            SyllabusDigest.Item {
            val s = res.syllabus
            return when {
                s == null || !res.ok -> SyllabusDigest.Item(
                    course.kogiCd, course.kogiNm, SyllabusDigest.Status.ERROR,
                )
                s.notRegistered -> SyllabusDigest.Item(
                    course.kogiCd, course.kogiNm, SyllabusDigest.Status.NOT_REGISTERED,
                )
                s.bodyFetched && res.body.isNotEmpty() -> SyllabusDigest.Item(
                    course.kogiCd, course.kogiNm, SyllabusDigest.Status.REGISTERED,
                    detail = SyllabusHtml.parse(res.body),
                )
                else -> SyllabusDigest.Item(
                    course.kogiCd, course.kogiNm, SyllabusDigest.Status.ERROR,
                )
            }
        }

        fun step(index: Int) {
            if (index >= courses.size) {
                viewModel.finishSyllabusDigest()
                return
            }
            val course = courses[index]

            // 1. キャッシュにあれば通信しない
            val cached = viewModel.cachedSyllabus(course.kogiCd, nendo)
            if (cached != null) {
                viewModel.updateDigestItem(course.kogiCd, cached)
                webView.post { step(index + 1) }
                return
            }

            fun finish(res: ApiResponse) {
                val item = toItem(course, res)
                viewModel.cacheSyllabus(course.kogiCd, nendo, item)
                viewModel.updateDigestItem(course.kogiCd, item)
                step(index + 1)
            }

            /*
             * 2. トークンの使い回し。
             *
             * トークンは画面（kinoId=3000230）に対して発行される。参照画面に一度
             * 入っていれば、講義コードを変えてもそのまま叩けるはず、という読み。
             * 通れば 1 科目ごとのページ遷移（実測 約400ms）が丸ごと消える。
             *
             * 読みが外れた場合は tokenRejected が立つので、その科目だけ
             * 従来どおり遷移してから取り直す。壊れずに degrade する。
             */
            val reused = syllabusFlow.fetchHere(webView, course.kogiCd, nendo) { res ->
                if (res.syllabus?.tokenRejected == true) {
                    Log.d("SyllabusDigest", "トークン使い回し不可。遷移して再試行: ${course.kogiCd}")
                    syllabusFlow.open(webView, course.kogiCd, nendo) { retry -> finish(retry) }
                } else {
                    finish(res)
                }
            }
            // 3. まだ参照画面に居ない（初回など）は遷移する
            if (!reused) {
                syllabusFlow.open(webView, course.kogiCd, nendo) { res -> finish(res) }
            }
        }
        step(0)
    }

    // --- ライフサイクル連動 -------------------------------------------------
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                Lifecycle.Event.ON_STOP ->
                    // Cookie を永続領域へ書き出し、プロセス終了でログイン状態が消えるのを防ぐ
                    WebViewSecurity.flushCookies()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            WebViewSecurity.flushCookies()
            // destroy() の前に必ず親から切り離す（アタッチ済みのまま破棄するとクラッシュしうる）
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    // 時間割が取れたら、待っていたシラバス一覧の取得を始める
    LaunchedEffect(state.timeTable, pendingDigest) {
        val table = state.timeTable
        if (pendingDigest && table != null && table.entries.isNotEmpty()) {
            pendingDigest = false
            runSyllabusDigest(table.distinctCourses)
        }
    }

    // 登録一覧を読み込む
    LaunchedEffect(Unit) {
        viewModel.setCardEntries(CredentialStore.entries(context))
    }

    /*
     * ログインの結末を判定する。
     *
     * フォームの送信自体は成功しても、ID やパスワードが違えばポータルは
     * ログイン画面を出し直す。送信後にまた LOGIN_REQUIRED を観測したら失敗とみなす。
     * 成功していれば needsLogin が false になり、上の遷移でホームへ移る。
     */
    LaunchedEffect(state.sessionState, state.loginState) {
        if (state.loginState !is LoginState.InProgress) return@LaunchedEffect
        when (state.sessionState) {
            SessionState.LOGIN_REQUIRED ->
                viewModel.setLoginState(
                    LoginState.Failed("ログインIDかパスワードが違うようです")
                )
            SessionState.LOGGED_IN_PROBABLE -> viewModel.setLoginState(LoginState.Idle)
            SessionState.UNKNOWN -> Unit
        }
    }

    // 応答が返らないまま止まり続けないよう、頭打ちを設ける
    LaunchedEffect(state.loginState) {
        if (state.loginState !is LoginState.InProgress) return@LaunchedEffect
        delay(25_000)
        if (viewModel.state.value.loginState is LoginState.InProgress) {
            viewModel.setLoginState(
                LoginState.Failed("応答がありませんでした。通信状況を確認してもう一度お試しください")
            )
        }
    }

    /**
     * カードタッチによる自動ログイン。
     *
     * 復号 → ポータル本来のログインフォームへ入力 → 本来のログインボタンを押す。
     * 利用者から見えるのは「タッチした → 入れた」だけだが、
     * 裏で通っているのは正規のログイン経路そのもの。
     */
    val loginWithCard: (String) -> Unit = { idm ->
        if (!CredentialStore.isRegistered(context, idm)) {
            viewModel.setLoginState(LoginState.Mismatch)
        } else {
            val credential = CredentialStore.load(context, idm)
            if (credential == null) {
                viewModel.setLoginState(
                    LoginState.Failed("保存された情報を読み出せませんでした。登録し直してください")
                )
            } else {
                viewModel.beginLogin()
                fetcher.submitLogin(webView, credential.loginId, credential.password) { ok, error ->
                    if (!ok) {
                        viewModel.setLoginState(
                            LoginState.Failed(error ?: "ログインできませんでした")
                        )
                    }
                    // 成功時はログイン判定の変化（needsLogin が false になる）に任せる
                }
            }
        }
    }

    /**
     * ログアウト。
     *
     * Cookie を消してポータルのセッションを切り、ログイン画面へ戻す。
     * 取得済みのシラバスや時間割も一緒に捨てる（次の利用者に見せないため）。
     *
     * 学生証の連携（保存した ID とパスワード）は**消さない**。
     * 「今のセッションを終える」ことと「この端末での連携をやめる」ことは別の意思なので、
     * 後者は学生証画面の「連携を解除する」で明示的に行わせる。
     */
    val logout: () -> Unit = {
        syllabusFlow.cancel()
        WebViewSecurity.clearLocalSession(webView)
        viewModel.onLocalSessionCleared()
        viewModel.setLoginState(LoginState.Idle)
        viewModel.setCardEntries(CredentialStore.entries(context))
        viewModel.navigate(PortalViewModel.AppScreen.LOGIN)
        webView.loadAllowedUrl(PortalConfig.START_URL)
    }

    val screen = state.screen
    val isLogin = screen == PortalViewModel.AppScreen.LOGIN
    val isPortalView = screen == PortalViewModel.AppScreen.PORTAL

    /*
     * 素のポータルを見せてよいのは、アプリ内ブラウザ（PORTAL）だけ。
     *
     * ログイン画面も独自 UI にしたので、通常の利用でポータルの生ページが
     * 見えることはない。WebView は破棄せず裏で動かし続け、
     * ログインの送信もデータ取得もそこで行う。
     */
    val webVisible = isPortalView

    /*
     * ログインが必要になったらログイン画面へ寄せ、済んだらホームへ戻す。
     * needsLogin は遷移中の UNKNOWN では揺れない（everLoggedIn を見ている）。
     *
     * 学生証の読み取りだけは例外にする。カードを読むのは端末の NFC の話で、
     * ポータルのセッションとは無関係。未ログインでも使えなければおかしい。
     */
    LaunchedEffect(state.needsLogin, screen) {
        val current = viewModel.state.value.screen
        if (current == PortalViewModel.AppScreen.STUDENT_CARD) return@LaunchedEffect
        if (state.needsLogin) {
            viewModel.navigate(PortalViewModel.AppScreen.LOGIN)
        } else if (current == PortalViewModel.AppScreen.LOGIN) {
            viewModel.navigate(PortalViewModel.AppScreen.HOME)
        }
    }

    // 戻るキー。重なっているパネル → アプリ内ブラウザの履歴 → ホーム、の順に畳む。
    val overlayOpen = state.showApi || state.showNetwork || state.showStructure
    BackHandler(enabled = overlayOpen || isPortalView || screen != PortalViewModel.AppScreen.HOME) {
        when {
            state.showApi -> viewModel.setShowApi(false)
            state.showNetwork -> viewModel.setShowNetwork(false)
            state.showStructure -> viewModel.setShowStructure(false)
            isPortalView && state.canGoBack -> webView.goBack()
            // 未ログイン中に学生証を見ていた場合はログイン画面へ戻す
            state.needsLogin -> viewModel.navigate(PortalViewModel.AppScreen.LOGIN)
            else -> viewModel.navigate(PortalViewModel.AppScreen.HOME)
        }
    }

    val screenTitle = when (screen) {
        PortalViewModel.AppScreen.LOGIN -> "ログイン"
        PortalViewModel.AppScreen.HOME -> "CampusPlan"
        PortalViewModel.AppScreen.TIMETABLE -> "履修時間割"
        PortalViewModel.AppScreen.SYLLABUS_LIST -> "シラバス一覧"
        PortalViewModel.AppScreen.SYLLABUS_SEARCH -> "シラバス検索"
        PortalViewModel.AppScreen.STUDENT_CARD -> "学生証"
        PortalViewModel.AppScreen.PORTAL -> state.portalTitle.ifBlank { "ポータル" }
        PortalViewModel.AppScreen.DEV_TOOLS -> "開発ツール"
    }

    Scaffold(
        topBar = {
            // ヘッダーは背景と地続きにして、下に細い境界線だけ引く
            Column {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                title = {
                    Text(screenTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (screen != PortalViewModel.AppScreen.HOME && !isLogin) {
                        TextButton(onClick = {
                            viewModel.navigate(
                                if (state.needsLogin) PortalViewModel.AppScreen.LOGIN
                                else PortalViewModel.AppScreen.HOME
                            )
                        }) { Text("戻る") }
                    }
                },
                actions = {
                    // ログアウトはホームからだけ。誤タップを避けるため確認を挟む
                    if (screen == PortalViewModel.AppScreen.HOME) {
                        TextButton(onClick = { showLogoutConfirm = true }) { Text("ログアウト") }
                    }
                    // 学生証の読み取りはログイン不要なので、ログイン画面からも入れる
                    if (isLogin) {
                        TextButton(onClick = {
                            viewModel.navigate(PortalViewModel.AppScreen.STUDENT_CARD)
                        }) { Text("学生証") }
                    }
                    // ログイン状態はホームとログイン画面でだけ出す。
                    // 各機能の画面では、遷移のたびに「状態不明」が点滅して邪魔になる。
                    if (screen == PortalViewModel.AppScreen.HOME || isLogin) {
                        SessionChip(state.sessionState)
                    }
                    // 調査ツールは開発用。製品の画面には出さない。
                    if (BuildConfig.DEBUG && !isLogin) {
                        TextButton(onClick = {
                            viewModel.navigate(PortalViewModel.AppScreen.DEV_TOOLS)
                        }) { Text("開発") }
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { innerPadding ->
        if (showLogoutConfirm) {
            AlertDialog(
                onDismissRequest = { showLogoutConfirm = false },
                title = { Text("ログアウトしますか") },
                text = {
                    Text(
                        if (state.cardEntries.isNotEmpty()) {
                            "ポータルのセッションを終了します。" +
                                "学生証の連携は残るので、次もかざすだけでログインできます。"
                        } else {
                            "ポータルのセッションを終了します。"
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showLogoutConfirm = false
                        logout()
                    }) { Text("ログアウト") }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutConfirm = false }) { Text("キャンセル") }
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ページ読み込みの進捗は、ポータルを見せている画面でだけ意味がある
            if (state.isLoading && webVisible) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (screen == PortalViewModel.AppScreen.DEV_TOOLS) {
            CompactStatus(state)
            StatusCard(state)

            ShortcutRow(
                shortcuts = PortalConfig.SHORTCUTS,
                onOpen = { shortcut ->
                    viewModel.setShowStructure(false)
                    webView.loadAllowedUrl(PortalConfig.absoluteUrl(shortcut.path))
                },
            )

            FlowRowSingle(
                label = "時間割を表示（DOMから取得）",
                onClick = {
                    probe.runTimeTable(webView) { t -> viewModel.onTimeTableResult(t) }
                },
            )

            // 回帰確認用: シラバスが実在する講義コードで一連の流れを通す。
            // 2026-08-28 実測で guid が返り、本文まで取得できることを確認済み。
            FlowRowSingle(
                label = "シラバス取得テスト (I243010)",
                onClick = { openSyllabus("I243010", "I243010") },
            )

            NetObserverRow(
                enabled = state.netObserverEnabled,
                onToggle = {
                    val next = !state.netObserverEnabled
                    viewModel.setNetObserverEnabled(next)
                    if (next) {
                        // 今開いているページにも即座に入れる
                        probe.installNetObserver(webView) { }
                    }
                },
                onRead = {
                    probe.readNetObserver(webView) { text ->
                        viewModel.onApiRequestStarted("記録した通信本文")
                        viewModel.onApiResponse(
                            ApiResponse(ok = true, status = 200, contentType = "application/json", body = text)
                        )
                    }
                },
            )

            ApiRow(
                endpoints = PortalConfig.API_ENDPOINTS,
                onCall = { endpoint ->
                    viewModel.onApiRequestStarted(endpoint.label)
                    fetcher.get(webView, PortalConfig.absoluteUrl(endpoint.path)) { res ->
                        viewModel.onApiResponse(res)
                    }
                },
                onCompare = {
                    val endpoint = PortalConfig.API_ENDPOINTS.first()
                    viewModel.onApiRequestStarted("比較テスト: ${endpoint.label}")
                    fetcher.compare(webView, PortalConfig.absoluteUrl(endpoint.path)) { res ->
                        viewModel.onApiResponse(res)
                    }
                },
            )

            ControlsRow(
                onHome = { webView.loadAllowedUrl(PortalConfig.START_URL) },
                onReload = { webView.reload() },
                onProbe = { probe.run(webView) { snapshot -> viewModel.onProbeResult(snapshot) } },
                onStructure = {
                    probe.runStructure(webView) { st -> viewModel.onStructureResult(st) }
                },
                onNetwork = { viewModel.setShowNetwork(!state.showNetwork) },
                networkCount = state.networkLog.size,
                onClearSession = {
                    WebViewSecurity.clearLocalSession(webView)
                    viewModel.onLocalSessionCleared()
                    webView.loadAllowedUrl(PortalConfig.START_URL)
                },
            )

            if (BuildConfig.DEBUG && state.observedExternalHosts.isNotEmpty()) {
                ObservedHostsSection(state.observedExternalHosts)
            }
            EventsSection(state.events, onClear = viewModel::clearEvents)
            } // DEV_TOOLS

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // 実際のログインはこの WebView 内で、ユーザー本人が行う。
                // アプリは ID / パスワードを一切受け取らないし、保存もしない。
                //
                // 構造パネルは WebView を composition から外さずに「上に重ねる」。
                // 出し入れのたびに View を付け外しすると描画状態が乱れるため。
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                    },
                )

                // ログイン画面ではカードのタッチを待ち受ける
                if (isLogin && state.cardEntries.isNotEmpty()) {
                    val cardScope = rememberCoroutineScope()
                    NfcReaderEffect(enabled = true) { read ->
                        cardScope.launch { loginWithCard(read.idm) }
                    }
                }

                /*
                 * ここが「素の Web を見せない」ための要。
                 *
                 * WebView は常にアタッチしたまま（破棄も付け外しもしない）だが、
                 * LOGIN / PORTAL 以外では必ず不透明なネイティブ画面で覆う。
                 * 覆う側を分岐で「出さない」ことが無いよう、else を必ず持たせる。
                 */
                if (!webVisible) {
                    when (screen) {
                        PortalViewModel.AppScreen.HOME -> HomePanel(
                            onOpenTimeTable = { openTimeTable(false) },
                            onOpenSyllabusList = { openTimeTable(true) },
                            onOpenSearch = openSearch,
                            onOpenStudentCard = {
                                viewModel.navigate(PortalViewModel.AppScreen.STUDENT_CARD)
                            },
                            onOpenPortalFeature = { shortcut ->
                                viewModel.openPortalView(shortcut.label)
                                webView.loadAllowedUrl(PortalConfig.absoluteUrl(shortcut.path))
                            },
                        )

                        PortalViewModel.AppScreen.TIMETABLE -> {
                            val timeTable = state.timeTable
                            if (timeTable != null && timeTable.entries.isNotEmpty()) {
                                TimeTablePanel(
                                    timeTable = timeTable,
                                    onClose = { viewModel.navigate(PortalViewModel.AppScreen.HOME) },
                                    onCourseClick = { course ->
                                        openSyllabus(course.kogiCd, course.kogiNm)
                                    },
                                    onDigestAll = { runSyllabusDigest(timeTable.distinctCourses) },
                                )
                            } else {
                                LoadingScreen("履修時間割を読み込んでいます…")
                            }
                        }

                        PortalViewModel.AppScreen.STUDENT_CARD -> StudentCardPanel(
                            result = state.cardRead,
                            entries = state.cardEntries,
                            onRead = viewModel::onCardRead,
                            onClear = viewModel::clearCardRead,
                            onRegister = { idm, label, loginId, password ->
                                val saved = CredentialStore.save(
                                    context, idm, label,
                                    CredentialStore.Credential(loginId, password),
                                )
                                if (saved) {
                                    viewModel.setCardEntries(CredentialStore.entries(context))
                                }
                                saved
                            },
                            onRemove = { idm ->
                                CredentialStore.remove(context, idm)
                                viewModel.setCardEntries(CredentialStore.entries(context))
                            },
                        )

                        PortalViewModel.AppScreen.SYLLABUS_SEARCH -> SyllabusSearchPanel(
                            state = state.search,
                            onKogiCdChange = viewModel::setSearchKogiCd,
                            onKogiNmChange = viewModel::setSearchKogiNm,
                            onSearch = runSearch,
                            onRowClick = { row -> openSyllabus(row.kogiCd, row.kogiNm) },
                        )

                        PortalViewModel.AppScreen.SYLLABUS_LIST -> {
                            val digest = state.syllabusDigest
                            if (digest != null) {
                                SyllabusDigestPanel(
                                    digest = digest,
                                    onClose = { viewModel.navigate(PortalViewModel.AppScreen.HOME) },
                                )
                            } else {
                                LoadingScreen("シラバスを集めています…")
                            }
                        }

                        // DEV_TOOLS は上の Column 側に出しているので、ここは
                        // WebView を隠すためだけの下地を敷く
                        PortalViewModel.AppScreen.DEV_TOOLS ->
                            Surface(modifier = Modifier.fillMaxSize()) {}

                        PortalViewModel.AppScreen.LOGIN -> LoginPanel(
                            state = state.loginState,
                            cardRegistered = state.cardEntries.isNotEmpty(),
                            onSubmit = { loginId, password ->
                                viewModel.beginLogin()
                                fetcher.submitLogin(webView, loginId, password) { ok, error ->
                                    if (!ok) {
                                        viewModel.setLoginState(
                                            LoginState.Failed(error ?: "ログインできませんでした")
                                        )
                                    }
                                }
                            },
                            onOpenCardSetting = {
                                viewModel.navigate(PortalViewModel.AppScreen.STUDENT_CARD)
                            },
                        )

                        // webVisible が true の画面。ここには来ない
                        PortalViewModel.AppScreen.PORTAL -> Unit
                    }
                }

                if (state.showApi) {
                    // シラバスの結果は独自 UI で出す。生 JSON/HTML を出す ApiPanel は
                    // それ以外の API（調査ツール）用に残す。
                    val syllabusMeta = state.apiResponse?.syllabus
                    if (state.apiLoading && state.apiLabel.startsWith("シラバス") || syllabusMeta != null) {
                        SyllabusPanel(
                            label = state.apiLabel,
                            loading = state.apiLoading,
                            response = state.apiResponse,
                            onClose = { viewModel.setShowApi(false) },
                        )
                    } else {
                        ApiPanel(
                            label = state.apiLabel,
                            loading = state.apiLoading,
                            response = state.apiResponse,
                            onClose = { viewModel.setShowApi(false) },
                        )
                    }
                }

                if (state.showNetwork) {
                    NetworkPanel(
                        observations = state.networkLog,
                        onClose = { viewModel.setShowNetwork(false) },
                        onClear = viewModel::clearNetworkLog,
                    )
                }

                val structure = state.structure
                if (state.showStructure && structure != null) {
                    StructurePanel(
                        structure = structure,
                        onClose = { viewModel.setShowStructure(false) },
                        onOpenLink = { href ->
                            viewModel.setShowStructure(false)
                            webView.loadAllowedUrl(href)
                        },
                    )
                }
            }
        }
    }
}

/**
 * ログイン状態の表示。
 *
 * 常時出る要素なので、枠付きチップではなく小さな点＋文字にして主張を抑える。
 * ここが目立つと、本来の主役である機能カードと視線を奪い合う。
 */
@Composable
private fun SessionChip(sessionState: SessionState) {
    val success = if (isSystemInDarkTheme()) AppColors.successDark else AppColors.successLight
    val (label, color) = when (sessionState) {
        SessionState.UNKNOWN -> "確認中" to MaterialTheme.colorScheme.onSurfaceVariant
        SessionState.LOGIN_REQUIRED -> "未ログイン" to MaterialTheme.colorScheme.error
        SessionState.LOGGED_IN_PROBABLE -> "ログイン中" to success
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(state: PortalViewModel.UiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 表示 URL はクエリを落としてある。
            // セッション ID などがクエリに載る作りの場合に、画面共有で漏らさないため。
            LabeledValue("URL", state.displayUrl)
            LabeledValue("title", UrlPolicy.truncate(state.pageTitle, 80).ifBlank { "(なし)" })
            LabeledValue("host allowlist", if (state.hostAllowed) "OK" else "対象外")

            val snapshot = state.snapshot
            if (snapshot != null) {
                Spacer(Modifier.height(4.dp))
                LabeledValue(
                    "DOM",
                    "ready=${snapshot.readyState} / password欄=${snapshot.passwordFieldCount} / " +
                        "form=${snapshot.formCount} / frame=${snapshot.frameCount} / " +
                        "link=${snapshot.linkCount} / textLen=${snapshot.textLength}",
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Phase 3: ショートカット。
 *
 * 遷移先は実機の DOM から採取した href。見た目のセレクタには一切依存しない。
 * 読み取り専用の画面だけを並べている（PortalConfig.SHORTCUTS のコメント参照）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutRow(
    shortcuts: List<PortalShortcut>,
    onOpen: (PortalShortcut) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shortcuts.forEach { s ->
            Button(onClick = { onOpen(s) }) { Text(s.label) }
        }
    }
}

/**
 * 操作ボタン。
 *
 * 横スクロールの Row にしていたところ、画面外のボタンに気付けない問題があったため
 * FlowRow による折り返し表示に変更した。ボタンは常に全部見えていること。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsRow(
    onHome: () -> Unit,
    onReload: () -> Unit,
    onProbe: () -> Unit,
    onStructure: () -> Unit,
    onNetwork: () -> Unit,
    networkCount: Int,
    onClearSession: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onClick = onStructure) { Text("構造を取得") }
        OutlinedButton(onClick = onNetwork) { Text("通信ログ ($networkCount)") }
        OutlinedButton(onClick = onHome) { Text("ポータル先頭") }
        OutlinedButton(onClick = onReload) { Text("再読込") }
        OutlinedButton(onClick = onProbe) { Text("ページ情報") }
        OutlinedButton(onClick = onClearSession) { Text("セッション破棄") }
    }
}

@Composable
private fun EventsSection(events: List<PortalEvent>, onClear: () -> Unit) {
    if (events.isEmpty()) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ブロック / エラー", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onClear) { Text("消去") }
            }
            events.forEach { event ->
                val line = when (event) {
                    is PortalEvent.NavigationBlocked ->
                        "遷移ブロック: ${event.redactedUrl} (${event.reason})"
                    is PortalEvent.SslErrorRejected ->
                        "SSL エラーで中止: ${event.redactedUrl} (${event.detail})"
                    is PortalEvent.LoadError ->
                        "読み込み失敗: ${event.redactedUrl} (${event.detail})"
                    is PortalEvent.DownloadBlocked ->
                        "ダウンロードをブロック: ${event.redactedUrl}"
                }
                Text(
                    text = "・$line",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * allowlist 外ホストへのサブリソース要求の観測結果（debug ビルドのみ表示）。
 *
 * ここに出たホストは「正規のログインフローに本当に必要か」を確認したうえで、
 * 必要なものだけ PortalConfig.ALLOWED_HOSTS に追加する。
 */
@Composable
private fun ObservedHostsSection(hosts: Set<String>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "allowlist 外ホスト観測 (debug)",
                style = MaterialTheme.typography.titleSmall,
            )
            hosts.sorted().forEach {
                Text(
                    "・$it",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Phase 2: 取得したページ構造の一覧。
 *
 * 目的は「シラバス」「履修時間割」がどのリンク / form から開くのかを、
 * 推測ではなく実物から特定すること。
 *
 * href を持つリンクはタップで遷移できる（allowlist を通してから読み込む）。
 * JavaScript 駆動のリンクはタップさせない。onclick を勝手に実行すると
 * 意図しない登録処理を叩く危険があるため、Phase 3 で個別に安全性を確認してから扱う。
 */
@Composable
private fun StructurePanel(
    structure: PageStructure,
    onClose: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    // 表示を短くするため、同一オリジンの接頭辞は落とす
    val origin = remember(structure.url) {
        runCatching {
            val u = android.net.Uri.parse(structure.url)
            "${u.scheme}://${u.host}"
        }.getOrDefault("")
    }

    fun shorten(href: String): String =
        if (origin.isNotEmpty() && href.startsWith(origin)) href.removePrefix(origin) else href

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("ページ構造", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "リンク ${structure.navLinks.size} / JS駆動 ${structure.scriptLinks.size} / " +
                            "form ${structure.forms.size} / button ${structure.buttons.size}" +
                            if (structure.truncated) " (打ち切りあり)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onClose) { Text("閉じる") }
            }

            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (structure.headings.isNotEmpty()) {
                    item { SectionHeader("見出し") }
                    items(structure.headings) { h ->
                        MonoRow("${h.tag}  ${h.text}")
                    }
                }

                if (structure.navLinks.isNotEmpty()) {
                    item { SectionHeader("リンク（タップで遷移）") }
                    items(structure.navLinks) { link ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenLink(link.href) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                link.text.ifBlank { "(テキストなし)" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                shorten(link.href),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                if (structure.scriptLinks.isNotEmpty()) {
                    item { SectionHeader("JavaScript 駆動リンク（遷移のみのものはタップ可）") }
                    items(structure.scriptLinks) { link ->
                        // onclick が「href へ遷移するだけ」と確認できるものに限りタップを許可する。
                        // 副作用のある onclick は実行しない。
                        val target = if (link.isPlainNavigation) origin + link.rawHref else null
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (target != null) Modifier.clickable { onOpenLink(target) }
                                    else Modifier
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                link.text.ifBlank { "(テキストなし)" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val detail = buildString {
                                if (link.id.isNotEmpty()) append("#${link.id} ")
                                if (link.rawHref.isNotEmpty()) append("href=${link.rawHref} ")
                                if (link.onclick.isNotEmpty()) append("onclick=${link.onclick} ")
                                if (link.data.isNotEmpty()) append(link.data.toString())
                            }
                            Text(
                                detail.ifBlank { "(手がかりなし)" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                if (structure.forms.isNotEmpty()) {
                    item { SectionHeader("フォーム（値は取得していません）") }
                    items(structure.forms) { form ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "${form.method.uppercase()} ${shorten(form.action)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            form.fields.forEach { f ->
                                Text(
                                    "    ${f.type}  name=${f.name}${if (f.id.isNotEmpty()) " id=${f.id}" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                if (structure.buttons.isNotEmpty()) {
                    item { SectionHeader("ボタン") }
                    items(structure.buttons) { b ->
                        MonoRow(
                            "\"${b.text}\" id=${b.id} name=${b.name}" +
                                if (b.onclick.isNotEmpty()) " onclick=${b.onclick}" else ""
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun MonoRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * Phase 4: 観測した通信の一覧。
 *
 * 表示しているのは「正規のブラウザ操作で実際に発生したリクエスト」だけで、
 * このアプリが独自にリクエストを投げているわけではない。
 * Authorization ヘッダは値を保持していないため、ここにも表示されない。
 */
@Composable
private fun NetworkPanel(
    observations: List<NetworkObservation>,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("通信ログ", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${observations.size} 件（新しい順・静的アセットは除外）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row {
                    TextButton(onClick = onClear) { Text("消去") }
                    TextButton(onClick = onClose) { Text("閉じる") }
                }
            }

            HorizontalDivider()

            if (observations.isEmpty()) {
                Text(
                    "まだ観測されていません。ページを操作すると記録されます。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(observations) { o ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "${o.method}  ${o.pathAndQuery}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        val tags = buildString {
                            if (o.isMainFrame) append("[main] ")
                            if (o.looksLikeApi) append("[API候補] ")
                            if (o.hasAuthorizationHeader) append("[Authorization有り] ")
                            if (o.accept.isNotEmpty()) append("accept=${o.accept} ")
                            if (o.requestedWith.isNotEmpty()) append("xrw=${o.requestedWith}")
                        }
                        if (tags.isNotBlank()) {
                            Text(
                                tags,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 4: API 呼び出しボタン。
 *
 * 叩くのは「正規フロントエンドが実際に使っている GET エンドポイント」だけ。
 * 認証は WebView が持つ正規セッションに任せており、アプリは資格情報を持たない。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiRow(
    endpoints: List<jp.naramed.campusplanpoc.model.ApiEndpoint>,
    onCall: (jp.naramed.campusplanpoc.model.ApiEndpoint) -> Unit,
    onCompare: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        endpoints.forEach { e ->
            OutlinedButton(onClick = { onCall(e) }) { Text("API: ${e.label}") }
        }
        OutlinedButton(onClick = onCompare) { Text("401原因の比較テスト") }
    }
}

/**
 * Phase 5: ネイティブ・ホーム。
 *
 * ログイン済みでダッシュボードにいるとき、生の CampusPlan の代わりに被せる
 * アプリの入口。各機能へは 1 タップで飛び、遷移先ではそれぞれの独自 UI
 * （時間割一覧・シラバス）が出る。
 *
 * WebView を消しているわけではない。裏でログインとデータ取得を担い続ける。
 * 「そのままポータルを見る」で一時的に生画面へ退避できる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePanel(
    onOpenTimeTable: () -> Unit,
    onOpenSyllabusList: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStudentCard: () -> Unit,
    onOpenPortalFeature: (PortalShortcut) -> Unit,
) {
    // ネイティブ UI を持つ機能と、まだポータルを開くしかない機能を分けて出す。
    // 後者はタップするとアプリ内ブラウザになることが分かる書き方にしてある。
    val nativePaths = setOf(
        "/portal/TimeTable",
        "/portal/External/RedirectLinkCpSmart?linkid=1900/3000090",
    )
    val portalOnly = PortalConfig.SHORTCUTS.filter { it.path !in nativePaths }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "履修",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            item {
                HomeCard(
                    title = "履修時間割",
                    subtitle = "登録している科目を一覧で見る",
                    onClick = onOpenTimeTable,
                )
            }
            item {
                HomeCard(
                    title = "シラバス一覧",
                    subtitle = "履修科目のシラバスをまとめて取得して読む",
                    onClick = onOpenSyllabusList,
                )
            }
            item {
                HomeCard(
                    title = "シラバス検索",
                    subtitle = "講義コードや名称で探す",
                    onClick = onOpenSearch,
                )
            }

            item {
                HomeCard(
                    title = "学生証を読む",
                    subtitle = "カードをかざして中身を確認する",
                    onClick = onOpenStudentCard,
                )
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "ポータルで開く",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            items(portalOnly) { shortcut ->
                Card(
                    onClick = { onOpenPortalFeature(shortcut) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = listCardColors(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = listCardBorder(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            shortcut.label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "ブラウザ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * ホームの主要導線カード。
 *
 * この画面の主役なので、一覧のカードより一段強く出す。
 * 色面で塗り、余白を広めに取って「押せる」ことを明示する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
            )
        }
    }
}

/**
 * Phase 5: シラバス検索の独自 UI。
 *
 * 入力欄も結果もネイティブ。ポータルの検索画面は裏で開いたままで、
 * 検索の実行時にだけ、そのフォームを動かして応答を受け取る
 * （リクエストの組み立てはページに任せる。js/syllabus_search.js 参照）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyllabusSearchPanel(
    state: SearchUiState,
    onKogiCdChange: (String) -> Unit,
    onKogiNmChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRowClick: (SyllabusSearchResult.Row) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = state.kogiCd,
                    onValueChange = onKogiCdChange,
                    label = { Text("講義コード") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.kogiNm,
                    onValueChange = onKogiNmChange,
                    label = { Text("講義名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onSearch,
                    enabled = !state.running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text(if (state.running) "検索中…" else "検索")
                }
            }

            if (state.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()

            val result = state.result
            when {
                state.running -> Text(
                    "検索しています…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )

                result != null && !result.ok -> Text(
                    result.error ?: "検索に失敗しました",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )

                result != null && result.rows.isEmpty() -> Text(
                    "該当する講義はありませんでした。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )

                result != null -> {
                    Text(
                        buildString {
                            append("${result.rows.size} 件")
                            if (result.truncated) append("（全 ${result.total} 件のうち先頭のみ）")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, bottom = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(result.rows) { row ->
                            Card(
                                onClick = { onRowClick(row) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                colors = listCardColors(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                border = listCardBorder(),
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        row.kogiNm.ifBlank { row.kogiCd },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        buildString {
                                            append("講義コード ${row.kogiCd}")
                                            if (row.kaikojiki.isNotBlank()) append(" ・ ${row.kaikojiki}")
                                            if (row.kyoin.isNotBlank()) append(" ・ ${row.kyoin}")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "タップでシラバスを開く",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                else -> Text(
                    "講義コードか講義名称を入れて検索してください。どちらも部分一致します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/**
 * 一覧のカード。
 *
 * 影ではなく「わずかに浮いた面 + 細い境界線」で区切る。
 * 影を重ねると要素が多い画面でうるさくなるため。
 */
@Composable
private fun listCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
)

@Composable
private fun listCardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/**
 * Phase 6: 学生証（FeliCa）の読み取りと、カード連携の登録。
 *
 * 1 台に複数枚を登録できる。共用端末で複数人が使う想定があるため、
 * カードごとに 1 件持ち、タッチされた IDm で引く。
 *
 * リーダーモードが有効なのはこの画面を開いている間だけ。
 * 読み取った IDm は、登録するまではどこにも保存されない。
 */
@Composable
private fun StudentCardPanel(
    result: FelicaReader.Result?,
    entries: List<CredentialStore.Entry>,
    onRead: (FelicaReader.Result) -> Unit,
    onClear: () -> Unit,
    onRegister: (idm: String, label: String, loginId: String, password: String) -> Boolean,
    onRemove: (idm: String) -> Unit,
) {
    val context = LocalContext.current
    val availability = remember(context) { nfcAvailability(context as? android.app.Activity) }

    // 読み取りコールバックはワーカースレッドで来るので、状態更新はメインへ戻す
    val scope = rememberCoroutineScope()
    NfcReaderEffect(enabled = availability == NfcAvailability.AVAILABLE) { read ->
        scope.launch { onRead(read) }
    }

    var label by rememberSaveable { mutableStateOf("") }
    var loginId by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<CredentialStore.Entry?>(null) }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("この登録を削除しますか") },
            text = {
                Text(
                    "「${target.label.ifBlank { target.loginId.ifBlank { target.idm } }}」の" +
                        "ログイン情報を、この端末から削除します。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(target.idm)
                    removeTarget = null
                    message = "削除しました"
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("キャンセル") }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (availability) {
                NfcAvailability.UNSUPPORTED -> item {
                    Text(
                        "この端末は NFC に対応していません。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                NfcAvailability.DISABLED -> item {
                    Text(
                        "NFC がオフになっています。端末の設定でオンにしてから、この画面を開き直してください。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                NfcAvailability.AVAILABLE -> {
                    // --- 新しいカードの登録 ---
                    if (result == null) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Column(modifier = Modifier.padding(24.dp)) {
                                    Text(
                                        "学生証をかざしてください",
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "端末の背面中央あたりにカードを当てると、登録できます。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                            .copy(alpha = 0.78f),
                                    )
                                }
                            }
                        }
                    } else {
                        val already = entries.any { it.idm.equals(result.idm, ignoreCase = true) }
                        item { CardField("カード識別子 (IDm)", result.idm) }
                        item {
                            Text(
                                if (already) {
                                    "このカードは登録済みです。入力して保存すると上書きします。"
                                } else {
                                    "このカードにログイン情報を紐づけると、次からはかざすだけでログインできます。"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = label,
                                onValueChange = { label = it },
                                label = { Text("名前（任意・誰のカードか分かるように）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = loginId,
                                onValueChange = { loginId = it },
                                label = { Text("ログインID") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("パスワード") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            Button(
                                onClick = {
                                    message = if (loginId.isBlank() || password.isBlank()) {
                                        "ID とパスワードを入力してください"
                                    } else if (
                                        onRegister(result.idm, label.trim(), loginId.trim(), password)
                                    ) {
                                        label = ""
                                        loginId = ""
                                        password = ""
                                        onClear()
                                        "登録しました"
                                    } else {
                                        "登録できませんでした"
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp),
                            ) { Text(if (already) "上書きして登録" else "このカードを登録") }
                        }
                        item {
                            OutlinedButton(
                                onClick = onClear,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("やめる") }
                        }
                        item {
                            Text(
                                "入力した情報は、端末の Keystore で暗号化してこの端末にだけ保存します。" +
                                    "サーバへ送ることはありません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    message?.let {
                        item {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    // --- 登録済みの一覧 ---
                    if (entries.isNotEmpty()) {
                        item {
                            Text(
                                "登録済み（${entries.size} 枚）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                        items(entries) { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                colors = listCardColors(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                border = listCardBorder(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.label.ifBlank {
                                                entry.loginId.ifBlank { "(名前なし)" }
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            buildString {
                                                if (entry.loginId.isNotBlank() &&
                                                    entry.label.isNotBlank()
                                                ) {
                                                    append(entry.loginId)
                                                    append(" ・ ")
                                                }
                                                append(entry.idm)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { removeTarget = entry }) { Text("削除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ログイン画面。ここも独自 UI にして、ポータルのログインページは見せない。
 *
 * 入力欄はネイティブだが、送信するのは裏の WebView にある **本物のログインフォーム**。
 * 値を入れて本物のログインボタンを押すので、認証の経路は正規のまま。
 *
 * @param cardRegistered カード連携済みなら、待機の案内を出してタッチを待つ
 */
@Composable
private fun LoginPanel(
    state: LoginState,
    cardRegistered: Boolean,
    onSubmit: (loginId: String, password: String) -> Unit,
    onOpenCardSetting: () -> Unit,
) {
    var loginId by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val busy = state is LoginState.InProgress

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (cardRegistered) {
                item { CardWaitingCard(state) }
                item {
                    Text(
                        "または ID とパスワードでログイン",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
            } else {
                item {
                    Text(
                        "CampusPlan にログイン",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = loginId,
                    onValueChange = { loginId = it },
                    label = { Text("ログインID") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("パスワード") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = { onSubmit(loginId.trim(), password) },
                    enabled = !busy && loginId.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) { Text(if (busy) "ログインしています…" else "ログイン") }
            }

            if (busy) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            (state as? LoginState.Failed)?.let { failed ->
                item {
                    Text(
                        failed.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (!cardRegistered) {
                item {
                    OutlinedButton(
                        onClick = onOpenCardSetting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("学生証でログインできるようにする") }
                }
            }

            item {
                Text(
                    "入力した内容はポータルのログイン画面へそのまま渡されます。" +
                        "アプリが保存するのは、学生証と紐づけて登録したときだけです。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 学生証のタッチ待ち。ログイン画面の主役 */
@Composable
private fun CardWaitingCard(state: LoginState) {
    val isError = state is LoginState.Failed || state is LoginState.Mismatch
    val title = when (state) {
        LoginState.InProgress -> "ログインしています…"
        LoginState.Mismatch -> "登録されていないカードです"
        is LoginState.Failed -> "ログインできませんでした"
        LoginState.Idle -> "学生証をかざしてください"
    }
    val body = when (state) {
        LoginState.InProgress -> "そのままお待ちください。"
        LoginState.Mismatch -> "登録した学生証と違うようです。下の入力からもログインできます。"
        is LoginState.Failed -> state.message
        LoginState.Idle -> "端末の背面中央あたりにカードを当てると、自動でログインします。"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (state is LoginState.InProgress) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CardField(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = listCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = listCardBorder(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** データ取得中に WebView を隠しておくための、ネイティブのつなぎ画面 */
@Composable
private fun LoadingScreen(message: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Phase 5: 時間割の全科目のシラバスをまとめた一覧。
 *
 * 「裏で時間割とシラバスを開き、UI では時間割の各科目のシラバスを一覧表示する」
 * という当初の目標にあたる画面。科目ごとにカードを出し、取得できたものは
 * 概要をたたんで表示、タップで全文へ展開する。未登録・取得中も 1 行で見せる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyllabusDigestPanel(
    digest: SyllabusDigest,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 見出しはアプリバーが出しているので、ここは進捗だけ
            Text(
                text = if (digest.running) {
                    "取得中… ${digest.doneCount}/${digest.total}"
                } else {
                    "${digest.total} 科目中 ${digest.registeredCount} 件にシラバスあり"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )

            if (digest.running) {
                LinearProgressIndicator(
                    progress = {
                        if (digest.total == 0) 0f else digest.doneCount.toFloat() / digest.total
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(digest.items) { item -> SyllabusDigestCard(item) }
            }
        }
    }
}

/** まとめ一覧の 1 科目。登録ありはタップで全文へ展開する。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyllabusDigestCard(item: SyllabusDigest.Item) {
    var expanded by remember(item.kogiCd, item.status) { mutableStateOf(false) }
    val registered = item.status == SyllabusDigest.Status.REGISTERED

    Card(
        onClick = { if (registered) expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = listCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = listCardBorder(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.kogiNm.ifBlank { item.kogiCd },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "講義コード ${item.kogiCd}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusBadge(item.status)
            }

            if (registered && item.detail != null) {
                Spacer(modifier = Modifier.height(6.dp))
                if (!expanded) {
                    // たたんだ状態では概要を 1 セクションだけ抜粋
                    val summary = item.detail.sections.firstOrNull {
                        it.label.contains("概要")
                    } ?: item.detail.sections.firstOrNull()
                    if (summary != null) {
                        Text(
                            summary.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "タップで全文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    item.detail.sections.forEach { section ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            section.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(section.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: SyllabusDigest.Status) {
    // 未登録は異常ではないので、警告色ではなく控えめなグレーで出す
    val success = if (isSystemInDarkTheme()) AppColors.successDark else AppColors.successLight
    val (label, color) = when (status) {
        SyllabusDigest.Status.PENDING -> "取得中" to MaterialTheme.colorScheme.onSurfaceVariant
        SyllabusDigest.Status.REGISTERED -> "あり" to success
        SyllabusDigest.Status.NOT_REGISTERED -> "未登録" to MaterialTheme.colorScheme.onSurfaceVariant
        SyllabusDigest.Status.ERROR -> "取得失敗" to MaterialTheme.colorScheme.error
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
}

/**
 * Phase 5: シラバスの独自 UI。
 *
 * webmvc が返した HTML を [SyllabusHtml] で (ラベル, 本文) に分解し、
 * セクションのカードとして出す。生 HTML は画面に出さない。
 */
@Composable
private fun SyllabusPanel(
    label: String,
    loading: Boolean,
    response: ApiResponse?,
    onClose: () -> Unit,
) {
    val meta = response?.syllabus
    // パースは本文が変わったときだけやり直す
    val detail: SyllabusDetail? = remember(response?.body) {
        if (meta != null && meta.bodyFetched && response.body.isNotEmpty()) {
            SyllabusHtml.parse(response.body)
        } else null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            detail != null && detail.title.isNotEmpty() -> detail.title
                            meta != null && meta.kogiNm.isNotEmpty() -> meta.kogiNm
                            else -> label.removePrefix("シラバス").trim().ifEmpty { "シラバス" }
                        },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = when {
                        loading -> "取得中…"
                        meta == null && response != null && !response.ok -> "取得できませんでした"
                        meta != null -> "講義コード ${meta.kogiCd}"
                        else -> ""
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onClose) { Text("閉じる") }
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()

            when {
                loading -> {
                    Text(
                        "シラバスを取得しています…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                meta != null && meta.notRegistered -> {
                    // 未登録は正常系。エラーの見た目にしない
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = listCardColors(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        border = listCardBorder(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("シラバス未登録", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "この講義（${meta.kogiCd}）のシラバスはポータルに登録されていません。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                detail != null && detail.sections.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 12.dp,
                        ),
                    ) {
                        items(detail.sections) { section ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        section.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(section.text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                else -> {
                    Text(
                        response?.error ?: "取得できませんでした",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** API 応答の生データ表示。整形は Phase 5 で行う。 */
@Composable
private fun ApiPanel(
    label: String,
    loading: Boolean,
    response: ApiResponse?,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("API: $label", style = MaterialTheme.typography.titleMedium)
                    val syllabus = response?.syllabus
                    val summary = when {
                        loading -> "取得中…"
                        response == null -> "-"
                        !response.ok -> "失敗: ${response.error}"
                        // 未登録は失敗ではない。データが無いだけなので、そう出す。
                        syllabus != null && syllabus.notRegistered ->
                            "シラバス未登録（${syllabus.kogiCd} はシラバスが登録されていません）"
                        syllabus != null && syllabus.bodyFetched ->
                            "${syllabus.kogiNm.ifEmpty { syllabus.kogiCd }} / 本文 ${response.body.length} 文字" +
                                (if (response.truncated) "（打ち切りあり）" else "")
                        syllabus != null && syllabus.guid.isEmpty() ->
                            "本文まで到達せず（HTTP ${syllabus.initStatus}" +
                                (syllabus.errorMsg?.let { " / $it" } ?: "") + "）"
                        else -> "HTTP ${response.status} / ${response.contentType} / " +
                            "${response.body.length} 文字" +
                            if (response.truncated) "（打ち切りあり）" else ""
                    }
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onClose) { Text("閉じる") }
            }

            HorizontalDivider()

            val body = response?.body.orEmpty()
            if (body.isEmpty()) {
                Text(
                    if (loading) "応答待ち…" else "本文なし",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                // 生 JSON をそのまま出す。長いので行に分割して LazyColumn に流す。
                val lines = remember(body) { body.chunked(90) }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(lines) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowSingle(label: String, onClick: () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Button(onClick = onClick) { Text(label) }
    }
}

/**
 * Phase 5: 履修時間割の専用表示。
 *
 * ここが本 PoC の目標そのもの。WebView のページを操作させるのではなく、
 * 取り出した構造化データを Compose 側の UI として見せている。
 *
 * データの出どころは DOM の data-cp-kogicd。
 * 見た目のクラス名や nth-child には依存していないので、
 * 画面デザインが変わっても壊れにくい。
 */
@Composable
private fun TimeTablePanel(
    timeTable: TimeTable,
    onClose: () -> Unit,
    onCourseClick: (jp.naramed.campusplanpoc.model.TimeTableEntry) -> Unit,
    onDigestAll: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 見出しはアプリバーが出しているので、ここは件数だけ
            Text(
                "科目 ${timeTable.distinctCourses.size} 件 / コマ ${timeTable.entries.size} 件",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )

            HorizontalDivider()

            if (timeTable.entries.isEmpty()) {
                Text(
                    "科目が見つかりませんでした。履修時間割のページを開いてから実行してください。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
                return@Column
            }

            // 全科目のシラバスを裏でまとめて取得して一覧にする（当初の目標）
            Button(
                onClick = onDigestAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(min = 52.dp),
            ) {
                Text("全科目のシラバスをまとめて取得")
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { SectionHeader("履修科目") }
                items(timeTable.distinctCourses) { course ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCourseClick(course) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(course.kogiNm, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "講義コード ${course.kogiCd}  ・ タップでシラバス取得",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    HorizontalDivider()
                }

                item { SectionHeader("表の構造（曜日・時限の対応付け用）") }
                items(timeTable.tables) { grid ->
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            "key=${grid.key}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "列見出し: ${grid.colHeaders.joinToString(" | ")}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "行見出し: ${grid.rowHeaders.joinToString(" | ") { it.text }}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/**
 * POST ボディ観測の操作。
 *
 * ページの JS を包む計装なので、押したときだけ有効になる。
 * 通常のプローブと違いページを書き換える点に注意。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetObserverRow(enabled: Boolean, onToggle: () -> Unit, onRead: () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onClick = onToggle) {
            Text(if (enabled) "本文記録 ON（押すとOFF）" else "本文記録 OFF（押すとON）")
        }
        OutlinedButton(onClick = onRead) { Text("本文記録 読出") }
    }
}

/** 1 行だけの状態表示。詳細は調査ツールを開いたときに出す。 */
@Composable
private fun CompactStatus(state: PortalViewModel.UiState) {
    Text(
        text = state.displayUrl.removePrefix("https://campusplanportal.naramed-u.ac.jp"),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/** 調査ツールの開閉トグル */
@Composable
private fun ToolsToggle(expanded: Boolean, onToggle: () -> Unit) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Text(if (expanded) "▲ 調査ツールを閉じる" else "▼ 調査ツール")
    }
}
