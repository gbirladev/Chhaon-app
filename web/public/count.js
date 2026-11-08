/* Visit counting for chhaon.site (GoatCounter, site code "chhaon"), 2026-11-07.
 * Our own few lines instead of GoatCounter's script, so no third-party code runs on the site. It asks GoatCounter's
 * pixel (https://chhaon.goatcounter.com/count) to add 1 to:
 *   - the page path (no query string, no #hash, so nothing from inside the app is ever sent);
 *   - the referring site (origin + path only);
 *   - "ref/CODE" as an event when the page was opened with ?ref=CODE (which creator / doctor link brought the visit).
 * No cookies, no storage, no names, numbers or health data. Skipped on localhost and when "Do Not Track" is on.
 * Also window.chhaonEvent(path, title): one anonymous event for other site scripts (/ad-hero.js "why/<reason>").
 * Stats: https://chhaon.goatcounter.com (owner login). If a CSP is ever added, it needs img-src https://chhaon.goatcounter.com.
 * A file, not inline: the production CSP is script-src 'self'. */
(function () {
  var END = "https://chhaon.goatcounter.com/count";
  var allowed = (location.hostname === "chhaon.site" || location.hostname === "www.chhaon.site") &&
    !(navigator.doNotTrack === "1" || window.doNotTrack === "1" || navigator.webdriver);
  function hit(params) {
    var q = [];
    for (var k in params) if (params[k]) q.push(k + "=" + encodeURIComponent(params[k]));
    q.push("rnd=" + Math.random().toString(36).slice(2));
    new Image().src = END + "?" + q.join("&");
  }
  // One anonymous event (e.g. "why/privacy" from /ad-hero.js). Defined everywhere so callers need no checks, but it sends
  // nothing where this file sends nothing (not chhaon.site, Do Not Track, automated browsers). Returns true when sent.
  window.chhaonEvent = function (path, title) {
    if (!allowed || !/^[A-Za-z0-9/_-]{1,64}$/.test(path)) return false;
    hit({ p: path, t: title || path, e: "true" });
    return true;
  };
  if (!allowed) return;
  var ref = "";
  try {
    var r = document.referrer ? new URL(document.referrer) : null;
    if (r && r.origin !== location.origin) ref = r.origin + r.pathname;
  } catch (e) { /* no referrer */ }
  var size = (screen.width || 0) + "," + (screen.height || 0) + "," + (window.devicePixelRatio || 1);
  hit({ p: location.pathname, t: document.title, r: ref, s: size });
  try {
    var code = new URLSearchParams(location.search).get("ref");
    if (code && /^[A-Za-z0-9_-]{1,32}$/.test(code)) hit({ p: "ref/" + code.toUpperCase(), t: "Referral link", e: "true", r: ref });
  } catch (e) { /* no code */ }
})();
