/*
 * 401 の原因を切り分けるための比較実験。
 *
 * 同じエンドポイントを 3 通りの方法で GET し、ステータスだけを比較する。
 *   A) jQuery $.ajax（ページ自身と同じライブラリ・同じ既定値）
 *   B) fetch + Cookie のみ（Authorization なし）
 *   C) fetch + Cookie + Authorization: Bearer <localStorage の accessToken>
 *
 * 方針:
 *   - 3 本を **逐次** 実行する。サーバーへ同時に投げない。
 *   - GET のみ。状態を変える操作はしない。
 *   - 返すのはステータスコードと本文の先頭だけ。トークンの値は返さない。
 */
(function (url, id) {
    "use strict";

    var results = {};

    function reply(extra) {
        try {
            var payload = {
                id: id,
                ok: true,
                status: 200,
                contentType: "application/json",
                body: JSON.stringify(results, null, 1)
            };
            if (extra) payload.error = extra;
            __campusPlanPocBridge.postMessage(JSON.stringify(payload));
        } catch (e) { /* ブリッジ無し */ }
    }

    function head(s) {
        return String(s == null ? "" : s).slice(0, 120);
    }

    var jq = window.jQuery || window.$;

    // --- A) jQuery $.ajax ---------------------------------------------
    function stepA(next) {
        if (typeof jq !== "function") {
            results.A_jquery = { skipped: "jQuery なし" };
            return next();
        }
        try {
            jq.ajax({
                url: url,
                type: "GET",
                success: function (data, textStatus, xhr) {
                    results.A_jquery = {
                        status: xhr.status,
                        len: (typeof data === "string") ? data.length : JSON.stringify(data).length,
                        head: head(typeof data === "string" ? data : JSON.stringify(data))
                    };
                    next();
                },
                error: function (xhr) {
                    results.A_jquery = { status: xhr.status, head: head(xhr.responseText) };
                    next();
                }
            });
        } catch (e) {
            results.A_jquery = { error: head(e) };
            next();
        }
    }

    // --- B) fetch + Cookie のみ ----------------------------------------
    function stepB(next) {
        fetch(url, {
            method: "GET",
            credentials: "include",
            headers: { "X-Requested-With": "XMLHttpRequest" }
        })
            .then(function (r) {
                return r.text().then(function (t) {
                    results.B_fetch_cookie_only = { status: r.status, len: t.length, head: head(t) };
                    next();
                });
            })
            .catch(function (e) {
                results.B_fetch_cookie_only = { error: head(e) };
                next();
            });
    }

    // --- C) fetch + Bearer ---------------------------------------------
    function stepC(next) {
        var headers = { "X-Requested-With": "XMLHttpRequest" };
        var tokenLen = 0;
        try {
            var t = localStorage.getItem("accessToken");
            if (t && t.charAt(0) === '"') { try { t = JSON.parse(t); } catch (e) {} }
            if (t) {
                headers["Authorization"] = "Bearer " + t;   // 値はここから外へ出さない
                tokenLen = t.length;
            }
        } catch (e) {}

        fetch(url, { method: "GET", credentials: "include", headers: headers })
            .then(function (r) {
                return r.text().then(function (txt) {
                    results.C_fetch_bearer = {
                        status: r.status, len: txt.length, head: head(txt), tokenLen: tokenLen
                    };
                    next();
                });
            })
            .catch(function (e) {
                results.C_fetch_bearer = { error: head(e) };
                next();
            });
    }

    // 逐次実行（サーバーに同時に投げない）
    results.url = url;
    stepA(function () {
        setTimeout(function () {
            stepB(function () {
                setTimeout(function () {
                    stepC(function () { reply(null); });
                }, 300);
            });
        }, 300);
    });
})(__URL__, __ID__);
