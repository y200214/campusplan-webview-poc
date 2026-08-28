/*
 * Phase 4: POST のリクエストボディを観測するための計装。
 *
 * ⚠ これまでのプローブと違い、**ページの JS を書き換える**。
 *    XMLHttpRequest.send と fetch を包んで記録するため。
 *
 * 守っていること:
 *   - 元の関数をそのまま呼ぶ。リクエストの内容は一切変えない。
 *   - 追加のリクエストを発行しない。
 *   - 記録するのは **リクエスト** のみ。レスポンス本文は触らない。
 *   - 対象は /cpsmart/ と /portal/api/ のみ。他は無視する。
 *   - 二重適用しない。
 *
 * 用途は「正規フロントエンドがどんな形で API を呼んでいるか」の調査に限る。
 */
(function () {
    "use strict";

    if (window.__pocNetLogInstalled) {
        return JSON.stringify({ installed: true, already: true, count: (window.__pocNetLog || []).length });
    }

    var MAX_RECORDS = 60;
    var MAX_BODY = 2000;
    window.__pocNetLog = [];

    function isTarget(u) {
        return /\/cpsmart\/|\/portal\/api\//.test(String(u || ""));
    }

    function push(rec) {
        if (window.__pocNetLog.length >= MAX_RECORDS) window.__pocNetLog.shift();
        window.__pocNetLog.push(rec);
    }

    /*
     * 秘匿すべきキー。
     *
     * このシステムの RPC は tempData.entryContext に
     * 認証トークンと本人特定情報を毎回載せてくる。
     * 調査に必要なのは「どんな検索条件を送っているか」であって、
     * 認証情報や個人情報ではないので、記録する前に落とす。
     */
    var REDACT_KEYS = {
        token: 1, accessToken: 1,
        userId: 1, userName: 1, gakusekiNo: 1, kojinId: 1, kyoinCd: 1,
        narikawariUserId: 1, narikawariUserNm: 1,
        cpClientPid: 1, cpClientPort: 1
    };

    function redact(value) {
        if (value === null || typeof value !== "object") return value;
        if (Array.isArray(value)) {
            var arr = [];
            for (var i = 0; i < value.length; i++) arr.push(redact(value[i]));
            return arr;
        }
        var out = {};
        for (var k in value) {
            if (!Object.prototype.hasOwnProperty.call(value, k)) continue;
            out[k] = REDACT_KEYS[k] ? "<redacted>" : redact(value[k]);
        }
        return out;
    }

    /*
     * 観測したリクエストから entryContext（認証トークンを含む）を
     * **ページ内だけ** に保持する。
     *
     * これは Kotlin 側へは絶対に渡さない。
     * 後続の API 呼び出しをページの中で組み立てるためだけに使う。
     * 画面ごとに kinoId 等が違うので scriptController をキーにする。
     */
    window.__pocCtx = window.__pocCtx || {};

    function keepContext(obj) {
        try {
            var ctx = obj && obj.tempData && obj.tempData.entryContext;
            if (ctx && ctx.scriptController) {
                window.__pocCtx[ctx.scriptController] = ctx;
            }
        } catch (e) {}
    }

    /** JSON なら秘匿処理をしてから返す。JSON でなければ中身を出さない。 */
    function redactBodyString(str) {
        try {
            var parsed = JSON.parse(str);
            keepContext(parsed);
            return JSON.stringify(redact(parsed));
        } catch (e) {
            // JSON でない本文は、形だけ分かれば十分なので中身を出さない
            return "[non-JSON body length=" + str.length + "]";
        }
    }

    function preview(body) {
        try {
            if (body == null) return "";
            if (typeof body === "string") return redactBodyString(body).slice(0, MAX_BODY);
            if (typeof URLSearchParams !== "undefined" && body instanceof URLSearchParams) {
                // クエリ形式は値を出さず、キー名だけにする
                var ks = [];
                body.forEach(function (v, k) { ks.push(k); });
                return "URLSearchParams keys: " + ks.join(", ");
            }
            if (typeof FormData !== "undefined" && body instanceof FormData) {
                // FormData は値を出さず、キー名だけにする
                var keys = [];
                body.forEach(function (v, k) { keys.push(k); });
                return "FormData keys: " + keys.join(", ");
            }
            return "[" + Object.prototype.toString.call(body) + "]";
        } catch (e) {
            return "[preview error]";
        }
    }

    // --- XMLHttpRequest ---------------------------------------------------
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function (method, url) {
        this.__pocMethod = method;
        this.__pocUrl = url;
        return origOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function (body) {
        try {
            if (isTarget(this.__pocUrl)) {
                push({
                    via: "xhr",
                    method: String(this.__pocMethod || ""),
                    url: String(this.__pocUrl || ""),
                    body: preview(body)
                });
            }
        } catch (e) { /* 記録に失敗しても通信は妨げない */ }
        return origSend.apply(this, arguments);
    };

    // --- fetch ------------------------------------------------------------
    var origFetch = window.fetch;
    if (typeof origFetch === "function") {
        window.fetch = function (input, init) {
            try {
                var u = (typeof input === "string") ? input : (input && input.url) || "";
                var m = (init && init.method) || (input && input.method) || "GET";
                if (isTarget(u)) {
                    push({ via: "fetch", method: String(m), url: String(u), body: preview(init && init.body) });
                }
            } catch (e) { /* 同上 */ }
            return origFetch.apply(this, arguments);
        };
    }

    window.__pocNetLogInstalled = true;
    return JSON.stringify({ installed: true, already: false, count: 0 });
})();
