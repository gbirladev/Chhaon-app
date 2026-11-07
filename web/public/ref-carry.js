/* Referral codes on the marketing pages (home, /hi/, /demo), 2026-11-07.
 * A doctor's or creator's link can point anywhere on the site (chhaon.site/?ref=CRE022, /demo?ref=CRE022), but only the app
 * saved the code (web/src/lib/referral.ts). This keeps it from being lost on the way:
 *   1. it stores the code exactly where the app looks for it (localStorage "chhaon-ref", same origin, same format, first code
 *      wins, 30 days), so the app picks it up at sign-in however the visitor gets there;
 *   2. it also adds ?ref= to the links into the app, the demo and the home pages, for phones where storage is blocked.
 * Counting only: nothing about the code is shown to anyone. A file, not inline: the production CSP is script-src 'self'. */
(function () {
  var KEY = "chhaon-ref", KEEP_MS = 30 * 86400000, CODE = /^[A-Za-z0-9_-]{1,32}$/;
  var code = null;
  try { code = new URLSearchParams(location.search).get("ref"); } catch (e) { return; }
  if (!code || !CODE.test(code)) return;
  code = code.toUpperCase();
  try {
    var old = JSON.parse(localStorage.getItem(KEY) || "null");
    var fresh = old && typeof old.code === "string" && typeof old.at === "number" && Date.now() - old.at <= KEEP_MS;
    if (!fresh) localStorage.setItem(KEY, JSON.stringify({ code: code, at: Date.now() }));
  } catch (e) { /* storage blocked: the links below still carry it */ }
  var carry = /^\/(app\/?|demo|hi\/?)?$/;
  document.querySelectorAll("a[href]").forEach(function (a) {
    try {
      var u = new URL(a.getAttribute("href"), location.href);
      if (u.origin !== location.origin || !carry.test(u.pathname) || u.searchParams.has("ref")) return;
      u.searchParams.set("ref", code);
      a.setAttribute("href", u.pathname + u.search + u.hash);
    } catch (e) { /* leave that link as it is */ }
  });
})();
