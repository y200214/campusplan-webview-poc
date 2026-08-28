/*
 * Phase 2: ページ構造の取得（読み取り専用）。
 *
 * 目的:
 *   「シラバス」「履修時間割」がどのリンク / form から開くのかを、
 *   推測ではなく実際の DOM から特定する。
 *
 * 個人情報の扱い（重要）:
 *   - input / textarea / select の **値は一切返さない**。name と type だけを返す。
 *   - テキストは長さ上限で切り詰める。
 *   - ページを書き換えない。イベントも発火させない。
 *
 * 返り値: JSON 文字列
 */
(function () {
    "use strict";

    var MAX_LINKS = 300;
    var MAX_TEXT = 80;
    var MAX_FORMS = 20;
    var MAX_FIELDS = 40;

    function txt(s, max) {
        if (!s) return "";
        return String(s).replace(/\s+/g, " ").trim().slice(0, max || MAX_TEXT);
    }

    function safe(fn, fallback) {
        try { return fn(); } catch (e) { return fallback; }
    }

    /** data-* 属性を拾う。JS駆動メニューでは遷移先がここに入っていることが多い。 */
    function dataAttrs(el) {
        var out = {};
        safe(function () {
            var attrs = el.attributes;
            for (var i = 0; i < attrs.length; i++) {
                var a = attrs[i];
                if (a.name.indexOf("data-") === 0) {
                    out[a.name] = txt(a.value, 120);
                }
            }
        }, null);
        return out;
    }

    var origin = safe(function () { return location.origin; }, "");

    // ---- リンク ---------------------------------------------------------
    // 通常の href リンクと、JavaScript で遷移するリンクを分けて収集する。
    // 後者は href が "javascript:void(0)" 等になり、href だけ見ても遷移先が分からないため。
    var navLinks = [];
    var scriptLinks = [];
    var seen = {};

    safe(function () {
        var anchors = document.querySelectorAll("a[href], area[href]");
        for (var i = 0; i < anchors.length; i++) {
            if (navLinks.length + scriptLinks.length >= MAX_LINKS) break;
            var a = anchors[i];
            var rawHref = a.getAttribute("href") || "";
            var resolved = safe(function () { return a.href; }, "");
            var label = txt(a.textContent, 60) || txt(a.getAttribute("title"), 60) || "";

            var entry = {
                text: label,
                href: resolved,
                rawHref: txt(rawHref, 200),
                id: txt(a.id, 60),
                cls: txt(a.className, 80),
                hasOnclick: a.hasAttribute("onclick"),
                onclick: txt(a.getAttribute("onclick"), 200),
                data: dataAttrs(a)
            };

            var key = entry.href + "|" + entry.rawHref + "|" + entry.text;
            if (seen[key]) continue;
            seen[key] = 1;

            var isScriptDriven =
                /^javascript:/i.test(rawHref) || rawHref === "#" || rawHref === "" || entry.hasOnclick;

            if (isScriptDriven) {
                scriptLinks.push(entry);
            } else if (origin && resolved.indexOf(origin) === 0) {
                // 同一オリジンのリンクだけを対象にする（外部サイトは扱わない）
                navLinks.push(entry);
            }
        }
    }, null);

    // ---- 見出し ---------------------------------------------------------
    var headings = [];
    safe(function () {
        var hs = document.querySelectorAll("h1, h2, h3");
        for (var i = 0; i < hs.length && headings.length < 40; i++) {
            var t = txt(hs[i].textContent, 100);
            if (t) headings.push({ tag: hs[i].tagName, text: t });
        }
    }, null);

    // ---- フォーム -------------------------------------------------------
    // action / method / フィールドの name と type のみ。値は取らない。
    var forms = [];
    safe(function () {
        for (var i = 0; i < document.forms.length && i < MAX_FORMS; i++) {
            var f = document.forms[i];
            var fields = [];
            safe(function () {
                var els = f.querySelectorAll("input, select, textarea");
                for (var j = 0; j < els.length && fields.length < MAX_FIELDS; j++) {
                    var e = els[j];
                    var type = (e.getAttribute("type") || e.tagName).toLowerCase();
                    // password の存在は記録するが、値は当然取らない
                    fields.push({
                        name: txt(e.name, 60),
                        id: txt(e.id, 60),
                        type: txt(type, 20)
                        // value は意図的に含めない
                    });
                }
            }, null);

            forms.push({
                name: txt(f.getAttribute("name"), 60),
                id: txt(f.id, 60),
                action: safe(function () { return f.action; }, ""),
                method: txt((f.getAttribute("method") || "get").toLowerCase(), 10),
                fieldCount: safe(function () { return f.elements.length; }, 0),
                fields: fields
            });
        }
    }, null);

    // ---- ボタン ---------------------------------------------------------
    var buttons = [];
    safe(function () {
        var bs = document.querySelectorAll("button, input[type=submit], input[type=button]");
        for (var i = 0; i < bs.length && buttons.length < 60; i++) {
            var b = bs[i];
            buttons.push({
                text: txt(b.textContent || b.value, 60),
                id: txt(b.id, 60),
                name: txt(b.name, 60),
                onclick: txt(b.getAttribute("onclick"), 200),
                data: dataAttrs(b)
            });
        }
    }, null);

    // ---- onclick から参照されているグローバル関数のソース -------------
    // 例: onclick="javascript:openwin(this)" の openwin が何をするのかを知りたい。
    //
    // 重要: 関数は **実行しない**。toString() でソースを読むだけ。
    //       実行すると意図しない画面遷移や登録処理が走る可能性があるため。
    var handlerSources = [];
    safe(function () {
        var names = {};
        var all = [].concat(navLinks, scriptLinks, buttons);
        for (var i = 0; i < all.length; i++) {
            var oc = all[i].onclick || "";
            var m = oc.match(/(?:^|[^\w.])([A-Za-z_$][\w$]*)\s*\(/);
            if (m && m[1]) names[m[1]] = true;
        }
        var skip = { "return": 1, "if": 1, "function": 1, "alert": 1, "window": 1 };
        for (var name in names) {
            if (skip[name]) continue;
            if (handlerSources.length >= 8) break;
            try {
                var fn = window[name];
                if (typeof fn === "function") {
                    handlerSources.push({
                        name: name,
                        source: String(fn).replace(/\s+/g, " ").slice(0, 1500)
                    });
                }
            } catch (e) { /* アクセスできない場合は無視 */ }
        }
    }, null);

    // ---- API 認証方式の調査 -------------------------------------------
    // 401 の原因を推測で潰さないために、ページがどうやって認証ヘッダを
    // 付けているのかを直接調べる。
    //
    // 重要: **値は一切返さない**。キー名と仕組み（関数のソース）だけ。
    //       トークンそのものは Kotlin 側へ渡さない。
    var ajaxInfo = safe(function () {
        var info = {
            hasJQuery: (typeof window.$ === "function" || typeof window.jQuery === "function"),
            ajaxSetupHeaderNames: [],
            beforeSendSource: "",
            localStorageKeys: [],
            sessionStorageKeys: []
        };

        var jq = window.jQuery || window.$;
        if (jq && jq.ajaxSettings) {
            if (jq.ajaxSettings.headers) {
                info.ajaxSetupHeaderNames = Object.keys(jq.ajaxSettings.headers);
            }
            if (typeof jq.ajaxSettings.beforeSend === "function") {
                info.beforeSendSource =
                    String(jq.ajaxSettings.beforeSend).replace(/\s+/g, " ").slice(0, 600);
            }
        }

        // キー名だけ。getItem は呼ばない。
        try { info.localStorageKeys = Object.keys(localStorage); } catch (e) {}
        try { info.sessionStorageKeys = Object.keys(sessionStorage); } catch (e) {}

        return info;
    }, null);

    return JSON.stringify({
        structureVersion: 3,
        ajax: ajaxInfo,
        handlerSources: handlerSources,
        url: safe(function () { return location.href; }, ""),
        path: safe(function () { return location.pathname; }, ""),
        title: txt(safe(function () { return document.title; }, ""), 120),
        readyState: safe(function () { return document.readyState; }, ""),
        navLinks: navLinks,
        scriptLinks: scriptLinks,
        headings: headings,
        forms: forms,
        buttons: buttons,
        truncated: (navLinks.length + scriptLinks.length) >= MAX_LINKS
    });
})();
