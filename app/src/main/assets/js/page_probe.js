/*
 * Phase 1 用の最小 DOM プローブ。
 *
 * 方針:
 *  - CampusPlan 固有のセレクタは一切書かない（実物の DOM を確認してから Phase 2 で追加する）。
 *  - 個人情報そのものを返さない。件数・長さ・種別など「構造」だけを返す。
 *  - ページを一切書き換えない（読み取り専用）。
 *  - 返り値は JSON 文字列。Kotlin 側で evaluateJavascript のコールバックとして受け取る。
 *
 * 注意: このスクリプトはトップレベル document にのみ適用される。
 *       フレーム構成のページでは frameCount > 0 になり、中身は別途扱う必要がある（Phase 2 で検討）。
 */
(function () {
    "use strict";

    function safe(fn, fallback) {
        try {
            return fn();
        } catch (e) {
            return fallback;
        }
    }

    var result = {
        probeVersion: 1,
        url: safe(function () { return location.href; }, ""),
        origin: safe(function () { return location.origin; }, ""),
        path: safe(function () { return location.pathname; }, ""),
        title: safe(function () { return document.title || ""; }, ""),
        readyState: safe(function () { return document.readyState || ""; }, ""),

        // ログイン画面判定に使う汎用シグナル（サービス固有の文字列には依存しない）
        passwordFieldCount: safe(function () {
            return document.querySelectorAll('input[type="password"]').length;
        }, 0),
        formCount: safe(function () { return document.forms.length; }, 0),

        // フレーム構成かどうか（旧世代の学務システムでは frameset が使われることがある）
        frameCount: safe(function () {
            return document.querySelectorAll("frame, iframe").length;
        }, 0),

        // 本文の分量。ログイン後のページかどうかの弱い補助シグナルとして使う。
        // 本文そのものは返さない（個人情報をアプリ側へ流さないため）。
        textLength: safe(function () {
            var body = document.body;
            return body && body.innerText ? body.innerText.length : 0;
        }, 0),

        linkCount: safe(function () { return document.querySelectorAll("a[href]").length; }, 0),

        error: null
    };

    return JSON.stringify(result);
})();
