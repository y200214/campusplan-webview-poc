/*
 * Phase 5: 履修時間割を構造化データとして取り出す。
 *
 * 方針:
 *   - 目印は data-cp-kogicd（講義コード）。これはサーバーが埋めている安定した属性で、
 *     見た目のクラス名や nth-child には依存しない。
 *   - 位置（曜日・時限）は <table> の rowIndex / cellIndex から求める。
 *     表のヘッダ行とヘッダ列も一緒に返し、対応付けは Kotlin 側で行う。
 *   - 読み取り専用。DOM を書き換えず、onclick も実行しない。
 */
(function () {
    "use strict";

    function txt(s, max) {
        if (!s) return "";
        return String(s).replace(/\s+/g, " ").trim().slice(0, max || 80);
    }

    function safe(fn, fb) {
        try { return fn(); } catch (e) { return fb; }
    }

    var entries = [];
    var tables = {};

    safe(function () {
        var nodes = document.querySelectorAll("[data-cp-kogicd]");
        for (var i = 0; i < nodes.length && entries.length < 200; i++) {
            var el = nodes[i];
            var cell = el.closest ? el.closest("td, th") : null;
            var row = cell ? cell.parentElement : null;
            var table = el.closest ? el.closest("table") : null;
            var tableKey = "";

            if (table) {
                tableKey = table.id || txt(table.className, 40) || "table";
                if (!tables[tableKey]) {
                    // 表のヘッダ行（曜日）とヘッダ列（時限）を集める
                    var colHeaders = [];
                    var rowHeaders = [];
                    safe(function () {
                        var ths = table.querySelectorAll("thead th, tr:first-child th");
                        for (var k = 0; k < ths.length && k < 20; k++) {
                            colHeaders.push(txt(ths[k].textContent, 20));
                        }
                        var rows = table.rows;
                        for (var r = 0; r < rows.length && r < 30; r++) {
                            var first = rows[r].cells[0];
                            rowHeaders.push({
                                rowIndex: rows[r].rowIndex,
                                text: first ? txt(first.textContent, 20) : ""
                            });
                        }
                    }, null);
                    tables[tableKey] = { colHeaders: colHeaders, rowHeaders: rowHeaders };
                }
            }

            entries.push({
                kogiCd: txt(el.getAttribute("data-cp-kogicd"), 40),
                kogiNm: txt(el.textContent, 80),
                tableKey: tableKey,
                rowIndex: (row && typeof row.rowIndex === "number") ? row.rowIndex : -1,
                cellIndex: (cell && typeof cell.cellIndex === "number") ? cell.cellIndex : -1,
                cellText: cell ? txt(cell.textContent, 120) : ""
            });
        }
    }, null);

    // 表ごとのヘッダ情報を配列にして返す
    var tableList = [];
    for (var key in tables) {
        tableList.push({
            key: key,
            colHeaders: tables[key].colHeaders,
            rowHeaders: tables[key].rowHeaders
        });
    }

    return JSON.stringify({
        url: safe(function () { return location.href; }, ""),
        title: txt(safe(function () { return document.title; }, ""), 100),
        // ページ見出し（例: 2026年度 前期 時間割）
        heading: safe(function () {
            var h = document.querySelector("h2");
            return h ? txt(h.textContent, 60) : "";
        }, ""),
        entries: entries,
        tables: tableList
    });
})();
