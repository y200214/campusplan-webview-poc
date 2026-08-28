package jp.naramed.campusplanpoc.ui

import androidx.lifecycle.ViewModel
import jp.naramed.campusplanpoc.model.LoginHeuristics
import jp.naramed.campusplanpoc.model.PageSnapshot
import jp.naramed.campusplanpoc.model.ApiResponse
import jp.naramed.campusplanpoc.model.NetworkObservation
import jp.naramed.campusplanpoc.model.PageStructure
import jp.naramed.campusplanpoc.model.PortalEvent
import jp.naramed.campusplanpoc.model.SessionState
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
class PortalViewModel : ViewModel() {

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
    )

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
            current.copy(
                snapshot = snapshot,
                sessionState = session,
                pageTitle = snapshot?.title ?: current.pageTitle,
            )
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
        _state.update {
            UiState(
                observedExternalHosts = it.observedExternalHosts,
            )
        }
    }
}
