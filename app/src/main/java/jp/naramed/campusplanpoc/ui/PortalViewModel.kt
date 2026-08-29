package jp.naramed.campusplanpoc.ui

import androidx.lifecycle.ViewModel
import jp.naramed.campusplanpoc.model.LoginHeuristics
import jp.naramed.campusplanpoc.model.PageSnapshot
import jp.naramed.campusplanpoc.model.ApiResponse
import jp.naramed.campusplanpoc.model.NetworkObservation
import jp.naramed.campusplanpoc.model.PageStructure
import jp.naramed.campusplanpoc.model.PortalEvent
import jp.naramed.campusplanpoc.model.SessionState
import jp.naramed.campusplanpoc.model.SearchUiState
import jp.naramed.campusplanpoc.auth.CredentialStore
import jp.naramed.campusplanpoc.nfc.FelicaReader
import jp.naramed.campusplanpoc.model.SyllabusDigest
import jp.naramed.campusplanpoc.model.SyllabusSearchResult
import jp.naramed.campusplanpoc.model.TimeTable
import jp.naramed.campusplanpoc.security.UrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 画面状態の保持。
 *
 * 注意:
 *  - WebView（View）はここに保持しない。Activity の Context をリークするため。
 *  - ここに置くのは「画面に出す値」だけ。認証情報は一切扱わない。
 *  - コールバックの一部（サブリソース観測）はワーカースレッドから来るので、
 *    状態更新はすべて MutableStateFlow.update（アトミック）で行う。
 */
/** ログインの進行状況。カードタッチと手入力の両方で使う */
sealed interface LoginState {
    data object Idle : LoginState
    /** カードは読めたが、登録済みのものと一致しない */
    data object Mismatch : LoginState
    data object InProgress : LoginState
    data class Failed(val message: String) : LoginState
}

class PortalViewModel : ViewModel() {

    /**
     * アプリの画面。
     *
     * 原則: [LOGIN] と [PORTAL] 以外では、WebView を必ず不透明なネイティブ画面で覆う。
     * WebView は破棄せず裏で動かし続ける（セッションとデータ取得のエンジン）。
     * 素のポータルが意図せず見えるのは、この 2 つ以外では不具合とみなす。
     */
    enum class AppScreen {
        /** 本人がポータル上でログインする画面。ここだけは WebView を見せる必要がある */
        LOGIN,
        HOME,
        /** 履修科目とそのシラバス。旧 TIMETABLE と統合した */
        SYLLABUS_LIST,
        SYLLABUS_SEARCH,
        STUDENT_CARD,
        /** アプリ内ブラウザ。ネイティブ UI が無い機能を「意図的に」ポータルで開く */
        PORTAL,
        /** 開発用（debug ビルドのみ） */
        DEV_TOOLS,
    }

    data class UiState(
        /** 表示用 URL。クエリは落としてある（スクリーンショット経由の漏洩を避けるため） */
        val displayUrl: String = "(未読み込み)",
        /** 現在ページのパスだけ。ネイティブ・ホームを出すかの判定に使う（クエリは含めない） */
        val currentPath: String = "",
        val pageTitle: String = "",
        val progress: Int = 0,
        val isLoading: Boolean = false,
        val sessionState: SessionState = SessionState.UNKNOWN,
        val snapshot: PageSnapshot? = null,
        /** Phase 2: ボタン押下時にだけ取得するページ構造 */
        val structure: PageStructure? = null,
        /** 構造一覧パネルを表示中か */
        val showStructure: Boolean = false,
        /** Phase 4: 観測した API らしきリクエスト（新しい順） */
        val networkLog: List<NetworkObservation> = emptyList(),
        val showNetwork: Boolean = false,
        /** Phase 4: 直近の API 取得結果 */
        val apiResponse: ApiResponse? = null,
        val apiLabel: String = "",
        val apiLoading: Boolean = false,
        val showApi: Boolean = false,
        /** Phase 5: DOM から取り出した履修時間割 */
        val timeTable: TimeTable? = null,
        val showTimeTable: Boolean = false,
        /** Phase 5: 時間割の全科目をまとめてシラバス取得した結果 */
        val syllabusDigest: SyllabusDigest? = null,
        val showDigest: Boolean = false,
        /**
         * POST ボディ観測の計装を有効にするか。
         * 有効な間は、ページを読み込むたびに自動で入れ直す
         * （計装はページ内の JS なので遷移すると消えるため）。
         */
        val netObserverEnabled: Boolean = false,
        val events: List<PortalEvent> = emptyList(),
        /** allowlist 外ホストへのサブリソース要求の観測結果（allowlist 確定の判断材料） */
        val observedExternalHosts: Set<String> = emptySet(),
        val hostAllowed: Boolean = false,
        val canGoBack: Boolean = false,
        /** 現在のアプリ画面 */
        val screen: AppScreen = AppScreen.HOME,
        /** アプリ内ブラウザのタイトル（どの機能を開いているか） */
        val portalTitle: String = "",
        /**
         * 一度でもログイン済みと判定できたか。
         *
         * sessionState はページ遷移のたびに UNKNOWN へ落ちるため、それだけで
         * ログイン画面へ戻すと、遷移のたびに素のポータルが一瞬見えてしまう。
         * 「明示的に LOGIN_REQUIRED を観測するまではログイン済みとみなす」ために持つ。
         */
        val everLoggedIn: Boolean = false,
        /** 時間割の取得待ち（ネイティブのローディングを出すため） */
        val timeTableLoading: Boolean = false,
        /** Phase 5: シラバス検索（独自 UI から入力して実行する） */
        val search: SearchUiState = SearchUiState(),
        /** Phase 6: 学生証の読み取り結果。画面を出ている間だけ持つ */
        val cardRead: FelicaReader.Result? = null,
        /** カード連携の登録一覧。1 台に複数枚を登録できる */
        val cardEntries: List<CredentialStore.Entry> = emptyList(),
        /** カードタッチによる自動ログインの進行状況 */
        val loginState: LoginState = LoginState.Idle,
    ) {
        /** ログイン画面を出すべきか */
        val needsLogin: Boolean
            get() = !everLoggedIn || sessionState == SessionState.LOGIN_REQUIRED
    }

    /**
     * 取得済みシラバスのキャッシュ。キーは 講義コード + 開講年度。
     *
     * **メモリ上だけに置く。ディスクへは書かない。**
     * このアプリはダウンロードを一律ブロックしている（端末にポータルの資料が
     * 残るのは管理外のデータ持ち出しになりうるため）。同じ理由でシラバス本文も
     * 永続化しない。プロセスが終われば消えるし、セッション破棄でも消す。
     *
     * 効果としては「同じ起動中に開き直したら即座に出る」まで。
     */
    private val syllabusCache = mutableMapOf<String, SyllabusDigest.Item>()

    fun cachedSyllabus(kogiCd: String, nendo: String): SyllabusDigest.Item? =
        syllabusCache["$kogiCd@$nendo"]

    fun cacheSyllabus(kogiCd: String, nendo: String, item: SyllabusDigest.Item) {
        // 失敗は次回やり直したいのでキャッシュしない
        if (item.status == SyllabusDigest.Status.ERROR ||
            item.status == SyllabusDigest.Status.PENDING
        ) return
        syllabusCache["$kogiCd@$nendo"] = item
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** URL からパスだけ取り出す（クエリ・フラグメントは落とす） */
    private fun pathOf(url: String?): String =
        url?.let { runCatching { android.net.Uri.parse(it).path.orEmpty() }.getOrDefault("") } ?: ""

    fun onPageStarted(url: String?) {
        _state.update {
            it.copy(
                displayUrl = UrlPolicy.redactForLog(url),
                currentPath = pathOf(url),
                hostAllowed = UrlPolicy.isAllowed(url),
                isLoading = true,
                progress = 0,
                // 遷移した時点で前ページの判定は無効にする
                sessionState = SessionState.UNKNOWN,
                snapshot = null,
                // 遷移したら前ページの構造は無効
                structure = null,
                showStructure = false,
            )
        }
    }

    fun onPageFinished(url: String?) {
        _state.update {
            it.copy(
                displayUrl = UrlPolicy.redactForLog(url),
                currentPath = pathOf(url),
                hostAllowed = UrlPolicy.isAllowed(url),
                isLoading = false,
                progress = 100,
            )
        }
    }

    fun onUrlChanged(url: String?) {
        _state.update {
            it.copy(
                displayUrl = UrlPolicy.redactForLog(url),
                currentPath = pathOf(url),
                hostAllowed = UrlPolicy.isAllowed(url),
            )
        }
    }

    fun onProgress(progress: Int) {
        _state.update { it.copy(progress = progress) }
    }

    fun onTitle(title: String?) {
        _state.update { it.copy(pageTitle = title.orEmpty()) }
    }

    fun onCanGoBackChanged(canGoBack: Boolean) {
        _state.update { it.copy(canGoBack = canGoBack) }
    }

    /** プローブ結果を反映し、ログイン状態を推定し直す */
    fun onProbeResult(snapshot: PageSnapshot?) {
        _state.update { current ->
            val session = LoginHeuristics.estimate(snapshot, current.hostAllowed)
            val everLoggedIn = when (session) {
                SessionState.LOGGED_IN_PROBABLE -> true
                // ログインフォームが見えたら、明示的にログアウト扱いへ戻す
                SessionState.LOGIN_REQUIRED -> false
                SessionState.UNKNOWN -> current.everLoggedIn
            }
            current.copy(
                snapshot = snapshot,
                sessionState = session,
                everLoggedIn = everLoggedIn,
                pageTitle = snapshot?.title ?: current.pageTitle,
            )
        }
    }

    // --- 画面遷移 -----------------------------------------------------------

    fun navigate(screen: AppScreen) {
        _state.update {
            it.copy(
                screen = screen,
                // 画面を移ったら重なっているパネルは畳む
                showApi = false,
                showNetwork = false,
                showStructure = false,
            )
        }
    }

    /** ネイティブ UI が無い機能を、アプリ内ブラウザとして意図的に開く */
    fun openPortalView(title: String) {
        _state.update { it.copy(screen = AppScreen.PORTAL, portalTitle = title) }
    }

    /**
     * 学生証を読み取った。
     *
     * この値は永続化しない。画面を離れれば消える。
     * 読み取れた事実を見せるだけで、まだ何にも紐づけない。
     */
    fun onCardRead(result: FelicaReader.Result) {
        _state.update { it.copy(cardRead = result) }
    }

    fun setCardEntries(entries: List<CredentialStore.Entry>) {
        _state.update { it.copy(cardEntries = entries) }
    }

    fun setLoginState(next: LoginState) {
        _state.update { it.copy(loginState = next) }
    }

    /**
     * ログインの送信を始める。
     *
     * ここで sessionState を UNKNOWN に戻すのが要点。
     * 送信する時点では画面がまだログインページなので sessionState は LOGIN_REQUIRED。
     * それを残したまま結果判定を始めると、送信直後に「ID かパスワードが違う」と
     * 誤判定してしまう（実機で発生）。判定材料を一度捨てて、
     * 送信後の新しいプローブ結果だけで判断させる。
     */
    fun beginLogin() {
        _state.update {
            it.copy(loginState = LoginState.InProgress, sessionState = SessionState.UNKNOWN)
        }
    }

    fun clearCardRead() {
        _state.update { it.copy(cardRead = null) }
    }

    fun setTimeTableLoading(loading: Boolean) {
        _state.update { it.copy(timeTableLoading = loading) }
    }

    // --- シラバス検索 -------------------------------------------------------

    fun setSearchKogiCd(v: String) {
        _state.update { it.copy(search = it.search.copy(kogiCd = v)) }
    }

    fun setSearchKogiNm(v: String) {
        _state.update { it.copy(search = it.search.copy(kogiNm = v)) }
    }

    fun onSearchStarted() {
        _state.update { it.copy(search = it.search.copy(running = true, result = null)) }
    }

    fun onSearchResult(result: SyllabusSearchResult) {
        _state.update {
            it.copy(search = it.search.copy(running = false, searched = true, result = result))
        }
    }

    fun onStructureResult(structure: PageStructure?) {
        _state.update { it.copy(structure = structure, showStructure = structure != null, showNetwork = false) }
    }

    fun setShowStructure(show: Boolean) {
        _state.update { it.copy(showStructure = show) }
    }

    fun onNetworkObserved(observation: NetworkObservation) {
        // 無制限に貯めない。新しい順に 150 件まで。
        _state.update { it.copy(networkLog = (listOf(observation) + it.networkLog).take(150)) }
    }

    fun setShowNetwork(show: Boolean) {
        _state.update { it.copy(showNetwork = show, showStructure = if (show) false else it.showStructure) }
    }

    fun clearNetworkLog() {
        _state.update { it.copy(networkLog = emptyList()) }
    }

    fun onApiRequestStarted(label: String) {
        _state.update {
            it.copy(
                apiLoading = true,
                apiLabel = label,
                apiResponse = null,
                showApi = true,
                showNetwork = false,
                showStructure = false,
            )
        }
    }

    fun onApiResponse(response: ApiResponse) {
        _state.update { it.copy(apiLoading = false, apiResponse = response) }
    }

    fun setShowApi(show: Boolean) {
        _state.update { it.copy(showApi = show) }
    }

    fun onTimeTableResult(table: TimeTable?) {
        _state.update { it.copy(timeTableLoading = false) }
        _state.update {
            // 時間割ページ以外で実行すると空の結果になる。
            // シラバス取得は cpsmart の画面で行う必要があるため、
            // 別ページへ移動しても取得済みの時間割は捨てずに表示できるようにする。
            val keepExisting = (table == null || table.entries.isEmpty()) &&
                (it.timeTable?.entries?.isNotEmpty() == true)
            it.copy(
                timeTable = if (keepExisting) it.timeTable else table,
                showTimeTable = keepExisting || table != null,
                showApi = false,
                showNetwork = false,
                showStructure = false,
            )
        }
    }

    fun setShowTimeTable(show: Boolean) {
        _state.update { it.copy(showTimeTable = show) }
    }

    /**
     * シラバスまとめの開始。対象科目を PENDING で並べ、パネルを開く。
     * 以降 1 件ずつ [updateDigestItem] で埋めていく。
     */
    fun startSyllabusDigest(courses: List<Pair<String, String>>) {
        val items = courses.map { (kogiCd, kogiNm) ->
            SyllabusDigest.Item(kogiCd, kogiNm, SyllabusDigest.Status.PENDING)
        }
        _state.update {
            it.copy(
                syllabusDigest = SyllabusDigest(
                    items = items, running = true, doneCount = 0, total = items.size,
                ),
                screen = AppScreen.SYLLABUS_LIST,
                showDigest = true,
                showTimeTable = false,
                showApi = false,
                showNetwork = false,
                showStructure = false,
            )
        }
    }

    /** まとめの 1 件を確定させ、進捗を進める */
    fun updateDigestItem(kogiCd: String, item: SyllabusDigest.Item) {
        _state.update { state ->
            val digest = state.syllabusDigest ?: return@update state
            val newItems = digest.items.map { if (it.kogiCd == kogiCd) item else it }
            state.copy(
                syllabusDigest = digest.copy(
                    items = newItems,
                    doneCount = (digest.doneCount + 1).coerceAtMost(digest.total),
                ),
            )
        }
    }

    /** まとめ完了。running を下ろす */
    fun finishSyllabusDigest() {
        _state.update { state ->
            val digest = state.syllabusDigest ?: return@update state
            state.copy(syllabusDigest = digest.copy(running = false))
        }
    }

    fun setShowDigest(show: Boolean) {
        _state.update { it.copy(showDigest = show) }
    }

    fun setNetObserverEnabled(enabled: Boolean) {
        _state.update { it.copy(netObserverEnabled = enabled) }
    }

    fun onEvent(event: PortalEvent) {
        // 直近 10 件だけ保持する（無制限に貯めない）
        _state.update { it.copy(events = (listOf(event) + it.events).take(10)) }
    }

    fun onExternalSubresourceObserved(host: String, @Suppress("UNUSED_PARAMETER") blocked: Boolean) {
        _state.update { it.copy(observedExternalHosts = it.observedExternalHosts + host) }
    }

    fun clearEvents() {
        _state.update { it.copy(events = emptyList()) }
    }

    fun onLocalSessionCleared() {
        // セッションを捨てたらキャッシュも捨てる（別人が使う可能性があるため）
        syllabusCache.clear()
        _state.update {
            UiState(
                observedExternalHosts = it.observedExternalHosts,
            )
        }
    }
}
