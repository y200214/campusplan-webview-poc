/*
 * Phase 5: 講義コードからシラバスを取得する。2 段階ある。
 *
 *   1. POST .../SystemD.Lead.Wsl.SyllabusSansho.App.SyllabusSanshoWebApi/initFindAndUpdate
 *        → {errorMsg, guid, kogiNm, sanshoUrl, isShowPDF, shareKey}
 *   2. GET  .../cpsmart/gakusei/wsl/webmvc/SyllabusSansho?TYPE=0&GUID=<guid>
 *        → シラバス本文
 *
 * どちらも、シラバス参照画面を開いたときにページ自身が呼んでいるものと同一。
 * こちらで登録処理を組み立てているわけではない（読み取りのみ）。
 *
 * sanshoUrl について（実測 2026-08-28）:
 *   ステージ 1 の応答には sanshoUrl というフィールドがあるが、
 *   **成功時でも null で返る**。ページ自身も sanshoUrl は使っておらず、
 *   guid から URL を組み立てている。ここでも同じにする。
 *
 * トークンについて（実測 2026-08-28）:
 *   この RPC は画面（kinoId）ごとに発行されたトークンを検証する。
 *   シラバス検索など別画面のコンテキストを流用すると
 *     {"errorMessages":[{"message":"{0}の値が不正です。","args":["Token"]}]}
 *   が HTTP 400 で返る。CSRF 保護が効いている状態であり、迂回はしない。
 *   呼び出し側（PortalConfig.syllabusSanshoUrl）でシラバス参照画面へ遷移させ、
 *   ページ自身にトークンを発行させてから本スクリプトを実行すること。
 *   遷移直後は SPA がまだコンテキストを登録していないので、少し待つ。
 *
 * 認証情報の扱い（重要）:
 *   entryContext（token 含む）は window.__pocCtx に **現在のページのメモリ上だけ**
 *   保持されている。ここでそれを読んでリクエストに載せるが、
 *   Kotlin 側へは渡さないし、localStorage / sessionStorage にも保存しない。
 *   Kotlin が受け取るのはレスポンス本文と、下の syllabus メタ情報だけ。
 */
(function (kogiCd, nendo, id) {
    "use strict";

    var MAX_BODY = 200000;

    // シラバス参照画面へ遷移した直後は、SPA がまだ initFindAndUpdate を
    // 呼び終わっていない。その画面のコンテキストが載るまで待つ。
    var CTX_TRIES = 20;
    var CTX_INTERVAL_MS = 250;

    function reply(obj) {
        try {
            obj.id = id;
            __campusPlanPocBridge.postMessage(JSON.stringify(obj));
        } catch (e) { /* ブリッジ無し */ }
    }

    /*
     * SyllabusSansho は /cpsmart/gakusei/wsl/WebRoot 配下にある。
     * ダッシュボード系（/com/WebRoot）を流用すると
     * 「service class not found」になるため、wsl スキーマを優先する。
     */
    function isWsl(c) {
        return !!(c && c.webRootUrl && c.webRootUrl.indexOf("/wsl/WebRoot") >= 0);
    }

    /** 本命。シラバス参照画面そのもののコンテキスト。 */
    function exactContext() {
        var m = window.__pocCtx || {};
        return m.SyllabusSansho ? { ctx: m.SyllabusSansho, source: "SyllabusSansho" } : null;
    }

    /*
     * 待っても本命が出てこなかったときの退避路。
     * トークンは通らない見込みだが、「なぜ通らないか」を切り分けられるよう
     * 従来どおり流用を試みて、contextSource に流用元を残す。
     */
    function fallbackContext() {
        var m = window.__pocCtx || {};
        var picked = null;
        var source = "";
        if (m.SyllabusKensaku) {
            picked = m.SyllabusKensaku;
            source = "SyllabusKensaku(流用)";
        }
        if (!picked) {
            for (var k in m) {
                if (isWsl(m[k])) { picked = m[k]; source = k + "(wsl流用)"; break; }
            }
        }
        if (!picked) {
            for (var k2 in m) { picked = m[k2]; source = k2 + "(流用)"; break; }
        }
        if (!picked) return null;

        // 流用時は画面識別子をシラバス参照のものに差し替える
        var c = JSON.parse(JSON.stringify(picked));
        c.kinoId = "3000230";
        c.scriptController = "SyllabusSansho";
        c.scriptAction = "index";
        c.schema = "wsl";
        c.proxySchema = "wsl";
        c.systemCd = "1900";
        c.routeValues = JSON.stringify({
            tenantKey: c.tenantKey, localeCd: c.localeCd, systemCd: "1900",
            kinoId: "3000230", schema: "wsl",
            scriptController: "SyllabusSansho", scriptAction: "index", id: ""
        });
        return { ctx: c, source: source };
    }

    function waitForContext(tries, done) {
        var hit = exactContext();
        if (hit) { done(hit); return; }
        if (tries <= 0) { done(fallbackContext()); return; }
        setTimeout(function () { waitForContext(tries - 1, done); }, CTX_INTERVAL_MS);
    }

    /** ステージ 1 の応答から必要なものだけ取り出す。トークン類は触らない。 */
    function parseInitResult(text) {
        var meta = {
            kogiCd: kogiCd,
            kogiNm: "",
            guid: "",
            errorMsg: null,
            notRegistered: false,
            initStatus: 0,
            bodyFetched: false
        };
        try {
            var o = JSON.parse(text);
            meta.guid = o && o.guid ? String(o.guid) : "";
            meta.kogiNm = o && o.kogiNm ? String(o.kogiNm) : "";
            meta.errorMsg = (o && o.errorMsg) ? String(o.errorMsg) : null;
            // MSG5 = そのコードのシラバスが登録されていない（通信もトークンも正常）
            meta.notRegistered = meta.errorMsg === "MSG5";
        } catch (e) { /* JSON でない（Token エラー等）ときは guid 無しのまま */ }
        return meta;
    }

    waitForContext(CTX_TRIES, function (hit) {
        if (!hit) {
            reply({
                ok: false,
                error: "認証コンテキスト未取得。シラバス参照画面が開けていない可能性があります"
            });
            return;
        }

        var ctx = hit.ctx;
        var contextSource = hit.source;

        // SyllabusSansho は必ず wsl スキーマ配下。流用時も wsl に寄せる。
        var webRoot = String(ctx.webRootUrl || "").replace(/\/(com|wsl)\/WebRoot$/, "/wsl/WebRoot");
        if (webRoot.indexOf("/wsl/WebRoot") < 0) {
            webRoot = (ctx.cpUrlDomain || location.origin) + "/cpsmart/gakusei/wsl/WebRoot";
        }
        ctx.webRootUrl = webRoot;

        var initUrl = webRoot +
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

        fetch(initUrl, {
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
                    var meta = parseInitResult(t);
                    meta.initStatus = res.status;

                    // guid が取れなければステージ 2 へ行けない。
                    // 未登録（MSG5）もトークンエラーもここで返す。本文は診断材料として残す。
                    if (!meta.guid) {
                        reply({
                            ok: true,
                            status: res.status,
                            contentType: res.headers.get("content-type") || "",
                            truncated: t.length > MAX_BODY,
                            body: t.slice(0, MAX_BODY),
                            contextSource: contextSource,
                            syllabus: meta
                        });
                        return;
                    }

                    // ステージ 2: 本文。URL は sanshoUrl ではなく guid から組む。
                    var bodyUrl = webRoot.replace(/\/WebRoot$/, "/webmvc/SyllabusSansho") +
                        "?TYPE=0&GUID=" + encodeURIComponent(meta.guid);

                    return fetch(bodyUrl, {
                        method: "GET",
                        credentials: "include",
                        headers: {
                            "X-Requested-With": "XMLHttpRequest",
                            "Accept": "*/*"
                        }
                    }).then(function (res2) {
                        return res2.text().then(function (t2) {
                            meta.bodyFetched = res2.status >= 200 && res2.status < 300;
                            reply({
                                ok: true,
                                status: res2.status,
                                contentType: res2.headers.get("content-type") || "",
                                truncated: t2.length > MAX_BODY,
                                body: t2.slice(0, MAX_BODY),
                                contextSource: contextSource,
                                syllabus: meta
                            });
                        });
                    }).catch(function (e2) {
                        reply({
                            ok: false,
                            status: res.status,
                            error: "本文取得に失敗: " + String(e2).slice(0, 200),
                            contextSource: contextSource,
                            syllabus: meta
                        });
                    });
                });
            })
            .catch(function (e) {
                reply({ ok: false, error: String(e).slice(0, 300), contextSource: contextSource });
            });
    });
})(__KOGICD__, __NENDO__, __ID__);
