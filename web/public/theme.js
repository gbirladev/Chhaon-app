/* Sets the colour theme before the first paint, so a dark phone never flashes white.
   Loaded synchronously from index.html (a separate file because the production CSP is script-src 'self': no inline scripts).
   Keep the key and the two colours in step with src/lib/theme.ts. Storage may be blocked: then it is simply System. */
(function () {
  var choice = null;
  try { choice = localStorage.getItem("chhaon-theme"); } catch (e) { /* storage blocked */ }
  var root = document.documentElement;
  if (choice === "light" || choice === "dark") { root.setAttribute("data-theme", choice); root.style.colorScheme = choice; }  // colorScheme: dark canvas even before the CSS arrives
  var dark = choice === "dark" || (choice !== "light" && !!window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
  var meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", dark ? "#14101f" : "#faf8fd");
})();
