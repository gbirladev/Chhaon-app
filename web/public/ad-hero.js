/* Ad visitors on the home page (/ and the generated /hi/), 2026-11-08.
 * A Meta ad links to chhaon.site/?ref=AD001 (later AD002 …). Those visitors tapped an ad about Papa in another city, so the
 * hero shows that same promise and one clear "Start free" button (home.html: the .ad-only block; the normal hero is .gen-only).
 *   1. Loaded in <head>: puts class "ad" on <html> before the first paint, so the normal hero never flashes.
 *      If this file fails to load, nothing changes and the normal page shows.
 *   2. Once the page is read: wires the "Not for you? Tell us why" card. One tap counts an anonymous event through
 *      window.chhaonEvent from /count.js ("why/<reason>": no names, no numbers, no health data; nothing is sent off chhaon.site
 *      or with Do Not Track), then says thank you. One answer per visit (sessionStorage; if storage is blocked, per page view).
 * A file, not inline: the production CSP is script-src 'self'. */
(function () {
  var root = document.documentElement, code = null;
  try { code = new URLSearchParams(location.search).get("ref"); } catch (e) { return; }
  if (root.getAttribute("data-site") !== "home" || !code || !/^AD[A-Za-z0-9_-]{0,30}$/i.test(code)) return;
  root.classList.add("ad");
  code = code.toUpperCase();

  var KEY = "chhaon-why-answered";
  function answered() { try { return sessionStorage.getItem(KEY) === "1"; } catch (e) { return false; } }
  function wire() {
    var box = document.getElementById("why-not");
    if (!box) return;
    var ask = box.querySelector(".why-ask"), thanks = box.querySelector(".thanks");
    if (answered()) { box.hidden = true; return; }
    box.addEventListener("click", function (ev) {
      var b = ev.target.closest ? ev.target.closest("button[data-why]") : null;
      if (!b || ask.hidden) return;
      try { sessionStorage.setItem(KEY, "1"); } catch (e) { /* storage blocked: the card still closes */ }
      if (window.chhaonEvent) window.chhaonEvent("why/" + b.getAttribute("data-why"), "Not for you (" + code + ")");
      ask.hidden = true;
      thanks.hidden = false;
    });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire);
  else wire();
})();
