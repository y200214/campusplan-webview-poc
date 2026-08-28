/*
 * Phase 5: シラバス検索を独自 UI から実行する。
 *
 * 方針（重要）:
 *   検索リクエストを自前で組み立てない。
 *   findPage のリクエスト本文は D_KOGI の全カラム定義を含む巨大な汎用検索条件で
 *   （実測 2000 文字超）、これを Kotlin 側で再現すると画面の仕様変更で即壊れる。
 *   代わりに **ページ自身の検索フォームに値を入れて、ページの検索ボタンを押す**。
 *   組み立てはページのフレームワークに任せ、こちらは結果だけ受け取る。
 *
 * やっていること:
 *   1. ラベル文字列から入力欄を探して値を入れる（Vue に気づかせるためイベントを送る）
 *   2. findPage の応答を 1 回だけ捕まえるフックを張る
 *   3. ページの「検索」ボタンをクリックする
 *   4. 応答から講義の行を取り出して返す
 *
 * 守っていること:
 *   - 追加のリクエストを発行しない。押すのはページ本来の検索ボタンだけ
 *   - 検索は読み取り操作。登録・更新系のボタンは一切触らない
 *   - フックは 1 回で外す。ページの通信内容は改変しない
 *   - 認証情報は読まないし返さない。返すのは検索結果の講義情報だけ
 */
(function (kogiCd, kogiNm, id) {
    "use strict";

    var TIMEOUT_MS = 15000;
    var MAX_ROWS = 300;
    var replied = false;

    function reply(obj) {
        if (replied) return;
        replied = true;
        try {
            obj.id = id;
            __campusPlanPocBridge.postMessage(JSON.stringify(obj));
        } catch (e) { /* ブリッジ無し */ }
    }

    function fail(message) {
        reply({ ok: false, error: message });
    }

    /** 末端要素のうち、テキストがラベルと一致するものを探す */
    function findLabelElement(labelText) {
        var all = document.querySelectorAll("label, span, div, th, td, p");
        for (var i = 0; i < all.length; i++) {
            var e = all[i];
            if (e.children.length !== 0) continue;
            if ((e.textContent || "").trim() === labelText) return e;
        }
        return null;
    }

    /**
     * ラベルの近傍にある入力欄を探す。
     * ラベルから親を数段たどり、その中の最初のテキスト入力を採る。
     * id もクラスも当てにせず、見た目の構造が変わっても追随しやすくする。
     */
    function findInputByLabel(labelText) {
        var label = findLabelElement(labelText);
        if (!label) return null;
        var node = label.parentElement;
        for (var up = 0; up < 5 && node; up++) {
            var inp = node.querySelector(
                'input[type="text"], input[type="search"], input:not([type])'
            );
            if (inp) return inp;
            node = node.parentElement;
        }
        return null;
    }

    /** Vue などのフレームワークに変更を認識させる形で値を入れる */
    function setValue(el, value) {
        var proto = (el.tagName === "TEXTAREA")
            ? HTMLTextAreaElement.prototype
            : HTMLInputElement.prototype;
        var desc = Object.getOwnPropertyDescriptor(proto, "value");
        if (desc && desc.set) {
            desc.set.call(el, value);
        } else {
            el.value = value;
        }
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
    }

    /** テキストが完全一致するクリック可能要素を探す */
    function findClickable(text) {
        var all = document.querySelectorAll("button, a, [role='button']");
        for (var i = 0; i < all.length; i++) {
            if ((all[i].textContent || "").trim() === text) return all[i];
        }
        return null;
    }

    /**
     * 応答のどこに行の配列があるか分からないので、
     * 「kogiCd を持つオブジェクトの配列」を深さ優先で探す。
     * ラッパーの形が変わっても拾える。
     */
    function findRows(value, depth) {
        if (!value || typeof value !== "object" || depth > 6) return null;
        if (Array.isArray(value)) {
            if (value.length && value[0] && typeof value[0] === "object" &&
                Object.prototype.hasOwnProperty.call(value[0], "kogiCd")) {
                return value;
            }
            for (var i = 0; i < value.length; i++) {
                var r = findRows(value[i], depth + 1);
                if (r) return r;
            }
            return null;
        }
        for (var k in value) {
            if (!Object.prototype.hasOwnProperty.call(value, k)) continue;
            var got = findRows(value[k], depth + 1);
            if (got) return got;
        }
        return null;
    }

    function text(v) {
        return (v === null || v === undefined) ? "" : String(v);
    }

    function handleResponse(raw) {
        var rows;
        try {
            rows = findRows(JSON.parse(raw), 0);
        } catch (e) {
            fail("検索結果を解析できませんでした");
            return;
        }
        if (!rows) {
            // 0 件のときも配列が見つからないことがある。空として扱う。
            reply({ ok: true, rows: [], truncated: false });
            return;
        }
        var out = [];
        for (var i = 0; i < rows.length && i < MAX_ROWS; i++) {
            var r = rows[i] || {};
            out.push({
                kogiCd: text(r.kogiCd),
                kogiNm: text(r.kogiNm),
                kaikojiki: text(r.kogiKaikojikiNm || r.kogiKaikojikiCd),
                kyoin: text(r.daihyoKyoinNm || r.daihyoKyoinCd),
                nenji: text(r.taishoNenji)
            });
        }
        reply({ ok: true, rows: out, truncated: rows.length > MAX_ROWS, total: rows.length });
    }

    // --- findPage の応答を 1 回だけ捕まえる ------------------------------
    function isFindPage(u) {
        return /KogiKensakuWebApi\/findPage/.test(String(u || ""));
    }

    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    var origFetch = window.fetch;
    var restored = false;

    function restore() {
        if (restored) return;
        restored = true;
        XMLHttpRequest.prototype.open = origOpen;
        XMLHttpRequest.prototype.send = origSend;
        window.fetch = origFetch;
    }

    XMLHttpRequest.prototype.open = function (method, url) {
        this.__pocSearchUrl = url;
        return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
        var xhr = this;
        if (isFindPage(xhr.__pocSearchUrl)) {
            xhr.addEventListener("load", function () {
                restore();
                handleResponse(xhr.responseText);
            });
            xhr.addEventListener("error", function () {
                restore();
                fail("検索リクエストが失敗しました");
            });
        }
        return origSend.apply(this, arguments);
    };
    window.fetch = function (input) {
        var url = (typeof input === "string") ? input : (input && input.url);
        var p = origFetch.apply(this, arguments);
        if (isFindPage(url)) {
            p.then(function (res) {
                res.clone().text().then(function (t) {
                    restore();
                    handleResponse(t);
                });
            }).catch(function () {
                restore();
                fail("検索リクエストが失敗しました");
            });
        }
        return p;
    };

    setTimeout(function () {
        restore();
        fail("検索結果が返ってきませんでした（タイムアウト）");
    }, TIMEOUT_MS);

    // --- 入力して検索を押す ---------------------------------------------
    var codeInput = findInputByLabel("講義コード");
    var nameInput = findInputByLabel("講義名称");
    if (!codeInput && !nameInput) {
        restore();
        fail("検索フォームが見つかりませんでした。シラバス検索の画面で実行してください");
        return;
    }
    if (codeInput) setValue(codeInput, kogiCd);
    if (nameInput) setValue(nameInput, kogiNm);

    var button = findClickable("検索") || findClickable("以上の条件で検索");
    if (!button) {
        restore();
        fail("検索ボタンが見つかりませんでした");
        return;
    }
    // Vue が入力を取り込むのを待ってから押す
    setTimeout(function () { button.click(); }, 150);
})(__KOGICD__, __KOGINM__, __ID__);
