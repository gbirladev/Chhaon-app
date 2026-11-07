/* Visit counting for chhaon.site (GoatCounter, site code "chhaon"), 2026-11-07.
 * Our own few lines instead of GoatCounter's script, so no third-party code runs on the site. It asks GoatCounter's
 * pixel (https://chhaon.goatcounter.com/count) to add 1 to:
 *   - the page path (no query string, no #hash, so nothing from inside the app is ever sent);
 *   - the referring site (origin + path only);
 *   - "ref/CODE" as an event when the page was opened with ?ref=CODE (which creator / doctor link brought the visit).
 * No cookies, no storage, no names, numbers or health data. Skipped on localhost and when "Do Not Track" is on.
 * Stats: https://chhaon.goatcounter.com (owner login). If a CSP is ever added, it needs img-src https://chhaon.goatcounter.com.
 * A file, not inline: the production CSP is script-src 'self'. */
(function () {
  if (location.hostname !== "chhaon.site" && location.hostname !== "www.chhaon.site") return;
  if (navigator.doNotTrack === "1" || window.doNotTrack === "1" || navigator.webdriver) return;
  var END = "https://chhaon.goatcounter.com/count";
  function hit(params) {
    var q = [];
    for (var k in params) if (params[k]) q.push(k + "=" + encodeURIComponent(params[k]));
    q.push("rnd=" + Math.random().toString(36).slice(2));
    new Image().src = END + "?" + q.join("&");
  }
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
