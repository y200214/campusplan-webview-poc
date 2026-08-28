/*
 * Phase 4/5: 講義コードからシラバスを取得する。
 *
 * 呼ぶのは、シラバス画面を表示したときにページ自身が呼んでいるのと同じ API:
 *   POST .../SystemD.Lead.Wsl.SyllabusSansho.App.SyllabusSanshoWebApi/initFindAndUpdate
 * 引数も同じ形にする（kogiCd と開講年度）。
 *
 * 認証情報の扱い（重要）:
 *   entryContext（token 含む）は window.__pocCtx に **現在のページのメモリ上だけ**
 *   保持されている。ここでそれを読んでリクエストに載せるが、
 *   Kotlin 側へは渡さないし、localStorage / sessionStorage にも保存しない。
 *   Kotlin が受け取るのはレスポンス本文だけ。
 *
 *   保存しない代償として、ページを離れるとコンテキストは失われる。
 *   そのため本機能は cpsmart の画面（シラバス検索など）を表示した状態で使う。
 *   「資格情報を持たない」方針を優先した結果の制約であり、意図的なもの。
 *
 * メソッド名について:
 *   initFindAndUpdate という名前だが、これはシラバスを画面表示するときに
 *   ページ自身が呼んでいるものと同一の呼び出しである。
 *   こちらで新たに登録処理を組み立てているわけではない。
 */
(function (kogiCd, nendo, id) {
    "use strict";

    var MAX_BODY = 200000;

    function reply(obj) {
        try {
            obj.id = id;
            __campusPlanPocBridge.postMessage(JSON.stringify(obj));
        } catch (e) { /* ブリッジ無し */ }
    }

    var ctxMap = window.__pocCtx || {};

    /*
     * コンテキストの選び方。
     *
     * SyllabusSansho は /cpsmart/gakusei/wsl/WebRoot 配下にある。
     * ダッシュボード系（/com/WebRoot）のコンテキストを流用すると
     * 「service class not found」になるため、wsl スキーマのものを優先する。
     */
    function isWsl(c) {
        return !!(c && c.webRootUrl && c.webRootUrl.indexOf("/wsl/WebRoot") >= 0);
    }

    var ctx = null;
    var contextSource = "";

    var preferred = ["SyllabusSansho", "SyllabusKensaku"];
    for (var i = 0; i < preferred.length; i++) {
        if (ctxMap[preferred[i]]) { ctx = ctxMap[preferred[i]]; contextSource = preferred[i]; break; }
    }
    if (!ctx) {
        for (var k in ctxMap) {
            if (isWsl(ctxMap[k])) { ctx = ctxMap[k]; contextSource = k + "(wsl流用)"; break; }
        }
    }
    if (!ctx) {
        for (var k2 in ctxMap) { ctx = ctxMap[k2]; contextSource = k2 + "(流用)"; break; }
    }

    if (ctx && contextSource !== "SyllabusSansho") {
        // 流用時は画面識別子をシラバス参照のものに差し替える
        ctx = JSON.parse(JSON.stringify(ctx));
        ctx.kinoId = "3000230";
        ctx.scriptController = "SyllabusSansho";
        ctx.scriptAction = "index";
        ctx.schema = "wsl";
        ctx.proxySchema = "wsl";
        ctx.systemCd = "1900";
        ctx.routeValues = JSON.stringify({
            tenantKey: ctx.tenantKey, localeCd: ctx.localeCd, systemCd: "1900",
            kinoId: "3000230", schema: "wsl",
            scriptController: "SyllabusSansho", scriptAction: "index", id: ""
        });
    }

    if (!ctx) {
        reply({
            ok: false,
            error: "認証コンテキスト未取得。シラバス検索の画面を表示した状態で、『本文記録』をONにしてから実行してください（トークンは保存しないため、その画面を離れると失われます）"
        });
        return;
    }

    // SyllabusSansho は必ず wsl スキーマ配下。
    // com スキーマのコンテキストを流用した場合でも wsl に寄せる。
    var webRoot = String(ctx.webRootUrl || "").replace(/\/(com|wsl)\/WebRoot$/, "/wsl/WebRoot");
    if (webRoot.indexOf("/wsl/WebRoot") < 0) {
        webRoot = (ctx.cpUrlDomain || location.origin) + "/cpsmart/gakusei/wsl/WebRoot";
    }
    ctx.webRootUrl = webRoot;

    var url = webRoot +
        "/SystemD.Lead.Wsl.SyllabusSansho.App.SyllabusSanshoWebApi/initFindAndUpdate";

    var payload = {
        methodParams: {
            langId: 0,
            kogiCd: kogiCd,
            kaikoNendo: nendo,
            syllabusKomokuPatternId: "2",
            userKubunCd: "",
            isOutputSuppress: ""
        },
        tempData: { entryContext: ctx }
    };

    fetch(url, {
        method: "POST",
        credentials: "include",
        headers: {
            "Content-Type": "application/json",
            "X-Requested-With": "XMLHttpRequest",
            "Accept": "*/*"
        },
        body: JSON.stringify(payload)
    })
        .then(function (res) {
            return res.text().then(function (t) {
                reply({
                    ok: true,
                    status: res.status,
                    contentType: res.headers.get("content-type") || "",
                    truncated: t.length > MAX_BODY,
                    body: t.slice(0, MAX_BODY),
                    contextSource: contextSource
                });
            });
        })
        .catch(function (e) {
            reply({ ok: false, error: String(e).slice(0, 300), contextSource: contextSource });
        });
})(__KOGICD__, __NENDO__, __ID__);
