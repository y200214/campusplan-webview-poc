/*
 * Phase 4: ログイン済みページのコンテキストから API を呼ぶ。
 *
 * 方針:
 *   - Android 側で認証を再実装しない。ページが持っている正規セッション（Cookie）を
 *     credentials:"include" でそのまま使う。
 *   - 送るヘッダは、正規フロントエンドが実際に送っているものと同じにする。
 *     ブラウザのふりをするための偽装ではなく、同じ経路を通すため。
 *   - GET のみ。状態を変える操作はここでは行わない。
 *
 * __URL__ と __ID__ は Kotlin 側が JSON エンコードした文字列リテラルに置換する。
 * 文字列連結でスクリプトを組み立てないため、URL に何が入っても JS として解釈されない。
 */
(function (url, id) {
    "use strict";

    var MAX_BODY = 200000;

    function reply(obj) {
        try {
            obj.id = id;
            __campusPlanPocBridge.postMessage(JSON.stringify(obj));
        } catch (e) {
            // ブリッジが無い場合は何もできない
        }
    }

    /*
     * 認証ヘッダ。
     *
     * このポータルの API は ASP.NET Web API の Bearer 認証で保護されており、
     * 正規のフロントエンドは localStorage の accessToken を
     * Authorization: Bearer <token> として送っている（401 応答と localStorage の
     * キー構成から確認済み）。ここでも同じ手順を踏む。
     *
     * 重要:
     *   - トークンは **このページの中でしか触らない**。
     *     Kotlin 側へ渡さないし、ログにも出さない。
     *   - 認証の迂回ではない。ユーザー本人のセッションが持っているトークンを、
     *     正規フロントエンドと同じ方法で同じサーバーへ送っているだけ。
     *   - サーバー側の認可判断は一切変わらない。権限が無ければ 403 が返るだけ。
     */
    var headers = {
        "X-Requested-With": "XMLHttpRequest",
        "Accept": "application/json, text/javascript, */*; q=0.01"
    };
    /*
     * トークンの「形状」だけを調べる診断関数。
     *
     * 返すのは長さ・引用符の有無・JWT かどうか・有効期限の3点だけ。
     * トークンの文字そのものや、JWT に含まれる氏名・ID などのクレームは
     * **一切返さない**（exp 以外は読まない）。
     */
    function inspectToken(raw) {
        var info = {
            present: !!raw,
            length: raw ? raw.length : 0,
            jsonQuoted: false,
            looksJwt: false,
            expEpoch: 0,
            expired: null
        };
        if (!raw) return info;

        var t = raw;
        if (t.charAt(0) === '"') {
            info.jsonQuoted = true;
            try { t = JSON.parse(t); } catch (e) {}
        }
        var parts = t.split(".");
        if (parts.length === 3) {
            info.looksJwt = true;
            try {
                var b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
                while (b64.length % 4) b64 += "=";
                var payload = JSON.parse(atob(b64));
                // exp 以外のクレームは読まない
                if (payload && payload.exp) {
                    info.expEpoch = payload.exp;
                    info.expired = (payload.exp * 1000) < Date.now();
                }
            } catch (e) {}
        }
        return info;
    }

    var tokenPresent = false;
    var tokenInfo = null;
    try {
        var rawToken = localStorage.getItem("accessToken");
        tokenInfo = inspectToken(rawToken);

        var token = rawToken;
        // JSON エンコードされて保存されている場合は剥がす
        if (token && token.charAt(0) === '"') {
            try { token = JSON.parse(token); } catch (e) {}
        }
        if (token) {
            headers["Authorization"] = "Bearer " + token;
            tokenPresent = true;
        }
    } catch (e) {
        // localStorage にアクセスできない場合は Cookie だけで試みる
    }

    try {
        fetch(url, {
            method: "GET",
            credentials: "include",
            headers: headers
        })
            .then(function (res) {
                return res.text().then(function (body) {
                    return {
                        status: res.status,
                        contentType: res.headers.get("content-type") || "",
                        body: body
                    };
                });
            })
            .then(function (r) {
                reply({
                    ok: true,
                    tokenPresent: tokenPresent,
                    tokenInfo: tokenInfo,
                    status: r.status,
                    contentType: r.contentType,
                    truncated: r.body.length > MAX_BODY,
                    body: r.body.slice(0, MAX_BODY)
                });
            })
            .catch(function (e) {
                reply({ ok: false, error: String(e).slice(0, 300) });
            });
    } catch (e) {
        reply({ ok: false, error: String(e).slice(0, 300) });
    }
})(__URL__, __ID__);
