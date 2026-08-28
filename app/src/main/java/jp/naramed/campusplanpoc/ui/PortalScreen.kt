package jp.naramed.campusplanpoc.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
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
import jp.naramed.campusplanpoc.model.PortalShortcut
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

    // 端末の戻るキーは WebView の履歴を優先する
    BackHandler(enabled = state.canGoBack) {
        webView.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CampusPlan PoC") },
                actions = { SessionChip(state.sessionState) },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (state.isLoading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CompactStatus(state)

            // Phase 3: 本来この PoC が目指す「1タップで目的の画面へ」の部分
            ShortcutRow(
                shortcuts = PortalConfig.SHORTCUTS,
                onOpen = { shortcut ->
                    viewModel.setShowStructure(false)
                    webView.loadAllowedUrl(PortalConfig.absoluteUrl(shortcut.path))
                },
            )

            // 調査用のボタン群は既定で畳んでおく。
            // 展開したままだと WebView の表示領域がほとんど残らないため。
            ToolsToggle(expanded = toolsExpanded, onToggle = { toolsExpanded = !toolsExpanded })

            if (toolsExpanded) {
            StatusCard(state)

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
            } // toolsExpanded

            EventsSection(state.events, onClear = viewModel::clearEvents)

            HorizontalDivider()

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

                val timeTable = state.timeTable
                if (state.showTimeTable && timeTable != null) {
                    TimeTablePanel(
                        timeTable = timeTable,
                        onClose = { viewModel.setShowTimeTable(false) },
                        onCourseClick = { course ->
                            openSyllabus(course.kogiCd, course.kogiNm)
                        },
                    )
                }

                if (state.showApi) {
                    ApiPanel(
                        label = state.apiLabel,
                        loading = state.apiLoading,
                        response = state.apiResponse,
                        onClose = { viewModel.setShowApi(false) },
                    )
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

@Composable
private fun SessionChip(sessionState: SessionState) {
    val (label, color) = when (sessionState) {
        SessionState.UNKNOWN -> "状態不明" to Color(0xFF9E9E9E)
        SessionState.LOGIN_REQUIRED -> "ログイン画面" to Color(0xFFEF6C00)
        SessionState.LOGGED_IN_PROBABLE -> "ログイン済み(推定)" to Color(0xFF2E7D32)
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color),
        modifier = Modifier.padding(end = 8.dp),
    )
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
                    Text(
                        timeTable.heading.ifBlank { "履修時間割" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "科目 ${timeTable.distinctCourses.size} 件 / コマ ${timeTable.entries.size} 件",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onClose) { Text("閉じる") }
            }

            HorizontalDivider()

            if (timeTable.entries.isEmpty()) {
                Text(
                    "科目が見つかりませんでした。履修時間割のページを開いてから実行してください。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
                return@Column
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
