/* Public website (home, /hi/, doctors, legal): applies the appearance before the first paint and wires the header picker.
   Loaded synchronously in <head> as a file (the production CSP is script-src 'self': no inline scripts).
   The setting is SHARED with the app (same origin, same keys and attributes as src/lib/theme.ts, src/lib/palette.ts and
   public/theme.js):
     chhaon-theme   "light" | "dark" | absent (= follow the device)   -> <html data-theme>
     chhaon-palette absent (= Chhaon violet) | "classic" (teal)        -> <html data-palette>
   The site offers only these two colours. Another app colour (teal, blue, saffron, rose, custom) is left as it is in storage
   and the site shows the default violet for it; choosing a colour here overwrites it, which also changes the app.
   Colours live in /site-theme.css; the theme-color values below are its --bg values. */
(function () {
  var root = document.documentElement;

  // Home page only (<html data-site="home">): old links and old home-screen icons.
  if (root.getAttribute("data-site") === "home") {
    // Emergency-card and family-invite links shared before the app moved to /app looked like chhaon.site/#/e/<token> or
    // #/join/<token>. Send them on to the app unchanged. hashchange covers a link pasted into a tab that already shows the page.
    var forward = function () { if (/^#\/(e|join)\//.test(location.hash)) location.replace("/app/" + location.hash); };
    forward();
    window.addEventListener("hashchange", forward);
    // An icon added to the home screen while the app lived at "/" still opens "/". Opened as an installed app (its own window,
    // not a browser tab), send it straight on to the app instead of showing the website. Normal visitors are not affected.
    var installed = (window.matchMedia && window.matchMedia("(display-mode: standalone)").matches) || window.navigator.standalone === true;
    if (installed && location.pathname === "/") location.replace("/app/" + location.search + location.hash);
  }

  var THEME_KEY = "chhaon-theme", PALETTE_KEY = "chhaon-palette";
  var BAR = { chhaon: ["#faf8fc", "#131119"], classic: ["#f8faf8", "#0f1716"] };
  function read(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }
  function write(key, value) { try { if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch (e) { /* storage blocked: applies until the page closes */ } }
  function themeChoice() { var t = read(THEME_KEY); return t === "light" || t === "dark" ? t : "system"; }
  function paletteChoice() { return read(PALETTE_KEY) === "classic" ? "classic" : "chhaon"; }
  var mq = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;

  function paint() {
    var t = themeChoice(), p = paletteChoice();
    if (t === "system") { root.removeAttribute("data-theme"); root.style.removeProperty("color-scheme"); }
    else { root.setAttribute("data-theme", t); root.style.colorScheme = t; }
    if (p === "classic") root.setAttribute("data-palette", "classic"); else root.removeAttribute("data-palette");
    var dark = t === "dark" || (t === "system" && !!mq && mq.matches);
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", BAR[p][dark ? 1 : 0]);
    var buttons = document.querySelectorAll("[data-ap-palette], [data-ap-theme]");
    for (var i = 0; i < buttons.length; i++) {
      var b = buttons[i], v = b.getAttribute("data-ap-palette") || b.getAttribute("data-ap-theme");
      b.setAttribute("aria-pressed", String(v === p || v === t));
    }
  }
  paint();
  if (mq && mq.addEventListener) mq.addEventListener("change", paint);
  // Another tab (the app or the site) changed the setting.
  window.addEventListener("storage", function (e) { if (e.key === THEME_KEY || e.key === PALETTE_KEY || e.key === null) paint(); });
  // Back/forward cache: the setting may have changed in the app meanwhile.
  window.addEventListener("pageshow", paint);

  function wire() {
    var btn = document.getElementById("ap-btn"), menu = document.getElementById("ap-menu");
    paint();
    if (!btn || !menu) return;
    function open(yes) {
      menu.hidden = !yes;
      btn.setAttribute("aria-expanded", String(yes));
      if (yes) { var first = menu.querySelector('button[aria-pressed="true"]') || menu.querySelector("button"); if (first) first.focus(); }
    }
    btn.addEventListener("click", function () { open(menu.hidden); });
    menu.addEventListener("click", function (e) {
      var b = e.target.closest ? e.target.closest("button") : null;
      if (!b) return;
      var p = b.getAttribute("data-ap-palette"), t = b.getAttribute("data-ap-theme");
      if (p) write(PALETTE_KEY, p === "classic" ? "classic" : null);
      if (t) write(THEME_KEY, t === "system" ? null : t);
      paint();
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && !menu.hidden) { open(false); btn.focus(); }
    });
    document.addEventListener("click", function (e) {
      if (!menu.hidden && !menu.contains(e.target) && !btn.contains(e.target)) open(false);
    });
    // Keyboard: leaving the menu with Tab closes it.
    menu.addEventListener("focusout", function (e) {
      if (e.relatedTarget && !menu.contains(e.relatedTarget) && e.relatedTarget !== btn) open(false);
    });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire); else wire();
})();
