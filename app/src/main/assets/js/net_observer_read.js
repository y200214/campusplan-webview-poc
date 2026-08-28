/*
 * 計装で溜めたリクエスト記録を読み出す（読み取りのみ）。
 */
(function () {
    "use strict";
    return JSON.stringify({
        installed: !!window.__pocNetLogInstalled,
        records: window.__pocNetLog || []
    });
})();
