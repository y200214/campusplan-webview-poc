/*
 * Phase 6: ログインフォームに入力して送信する。
 *
 * やっていること:
 *   ポータルの**本物のログインフォーム**に値を入れて、**本物のログインボタン**を押す。
 *   認証を偽装したり迂回したりはしない。人がやる操作をそのまま代行するだけ。
 *
 * 要素の探し方:
 *   id やクラス名に依存しない。type="password" の入力欄を起点にして、
 *   それを含むフォームの中から ID 欄と送信ボタンを見つける。
 *   画面の見た目が変わっても追随しやすくするため。
 *
 * 資格情報の扱い:
 *   引数として渡ってくるが、ページの外へは出さない。
 *   ログにも出さないし、ブリッジで返すのは成否だけ。
 */
(function (loginId, password, id) {
    "use strict";

    function reply(obj) {
        try {
            obj.id = id;
            __campusPlanPocBridge.postMessage(JSON.stringify(obj));
        } catch (e) { /* ブリッジ無し */ }
    }

    /** フレームワークに変更を認識させる形で値を入れる */
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

    var pw = document.querySelector('input[type="password"]');
    if (!pw) {
        reply({ ok: false, error: "ログインフォームが見つかりませんでした" });
        return;
    }

    // パスワード欄を含むフォーム（無ければ文書全体）を探索範囲にする
    var scope = pw.form || document;

    /*
     * ID 欄は「パスワード欄より前にある、テキスト系の入力」。
     * hidden や checkbox（ログイン状態を保持する等）を拾わないよう型で絞る。
     */
    var candidates = scope.querySelectorAll(
        'input[type="text"], input[type="email"], input[type="tel"], input:not([type])'
    );
    var idField = null;
    for (var i = 0; i < candidates.length; i++) {
        var el = candidates[i];
        if (el.offsetParent === null) continue;                    // 非表示は除く
        if (pw.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_PRECEDING) {
            idField = el;                                          // より後ろのものを採る
        }
    }
    if (!idField) {
        reply({ ok: false, error: "ID の入力欄が見つかりませんでした" });
        return;
    }

    setValue(idField, loginId);
    setValue(pw, password);

    /** テキストが一致する送信要素を探す */
    function findSubmit() {
        var byType = scope.querySelector('input[type="submit"], button[type="submit"]');
        if (byType) return byType;
        var all = scope.querySelectorAll('button, input[type="button"], a');
        for (var i = 0; i < all.length; i++) {
            var t = (all[i].value || all[i].textContent || "").trim();
            if (t === "ログイン" || t === "Login" || t === "ログインする") return all[i];
        }
        return null;
    }

    // 値がフレームワークに取り込まれるのを待ってから押す
    setTimeout(function () {
        var submit = findSubmit();
        if (submit) {
            reply({ ok: true, method: "click" });
            submit.click();
        } else if (pw.form) {
            // 送信ボタンが見つからない場合はフォームを直接送信する
            reply({ ok: true, method: "submit" });
            pw.form.submit();
        } else {
            reply({ ok: false, error: "ログインボタンが見つかりませんでした" });
        }
    }, 150);
})(__LOGINID__, __PASSWORD__, __ID__);
